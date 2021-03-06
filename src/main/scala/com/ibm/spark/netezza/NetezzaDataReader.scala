/**
 * (C) Copyright IBM Corp. 2015, 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.spark.netezza

import java.io.{BufferedReader, FileInputStream, InputStreamReader}
import java.sql.{Connection, PreparedStatement}

import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
import org.slf4j.LoggerFactory

/**
 * Creates reader for the given partitions. Reader fetches the data for the Netezza source table
 * by creating external table to tranfer from Netezza host system to a remote spark client using
 * named pipes.
 *
 * Executes the external table statement in separate thread to allow reading of data in parallel
 * from the named pipe by the RDD iterator.
 */
class NetezzaDataReader(conn: Connection,
                        table: String,
                        columns: Array[String],
                        filters: Array[Filter],
                        partition: NetezzaPartition,
                        schema: StructType) extends Iterator[NetezzaRow] {

  private val log = LoggerFactory.getLogger(getClass)

  val escapeChar: Char = '\\';
  val delimiter: Char = '\001';
  val recordParser = new NetezzaRecordParser(delimiter, escapeChar, schema)

  // thread for creating table
  var execThread: NetezzaUtils.StatementExecutorThread = null
  var closed: Boolean = false

  var pipe: java.io.File = null
  var input: BufferedReader = null
  var fis: FileInputStream = null
  var isr: InputStreamReader = null
  var nextLine: String = null
  var skipLF = false
  val sb = new StringBuilder(80)
  var nextRecord: NetezzaRow = null
  var firstCall = true
  var stmt: PreparedStatement = null

  /**
   * Start the external table executor that unloads the data. It is necessary to do
   * the setup in separate method instead of class constructor to allow caller to
   * execute close() call in error cases, otherwise job can hang forever.
   */
  def startExternalTableDataUnload() {
    pipe = NetezzaUtils.createPipe();
    val query = buildExternalTableQuery(pipe.toString)
    // prepare the statement before starting the  thread to catch any errors early.
    stmt = conn.prepareStatement(query)
    // start the thread that will populate the pipe
    execThread = new NetezzaUtils.StatementExecutorThread(conn, stmt, pipe);
    log.info("start thread to create external table..");
    execThread.start();

    // set up the input stream
    fis = new FileInputStream(pipe)
    isr = new InputStreamReader(fis)
    input = new BufferedReader(isr)
  }


  /**
   * Build externa table query for the specified options.
   *
   * @param pipeId id of the names pipe the Netezza system should write the data.
   * @return External table query to unload the data.
   */
  def buildExternalTableQuery(pipeId: String): String = {

    val baseQuery = {
      val whereClause = NetezzaFilters.getWhereClause(filters, partition)
      val colStrBuilder = new StringBuilder()
      if (columns.length > 0) {
        colStrBuilder.append(columns(0))
        columns.drop(1).foreach(col => colStrBuilder.append(",").append(col))
      } else {
        colStrBuilder.append("1")
      }
      s"SELECT $colStrBuilder FROM $table $whereClause"
    }
    // build external table initialized by base query
    val query: StringBuilder = new StringBuilder()
    query.append("CREATE EXTERNAL TABLE '" + pipeId + "'")
    query.append(" USING (delimiter '" + delimiter + "' ")
    query.append(" escapeChar '" + escapeChar + "' ")

    query.append(" REMOTESOURCE 'JDBC' NullValue 'null' BoolStyle 'T_F'")
    query.append(")")
    query.append(" AS " + baseQuery.toString() + " ")

    log.info("External Table Query: " + query)
    query.toString()
  }

  /**
    * Returns true if there are record in the pipe, otherwise false.
    */
  override def hasNext: Boolean = {
    // the end of the data when row is null
    if (nextLine == null) {
      if (firstCall) {
        nextLine = getNextLine()
        firstCall = false
        if (nextLine != null) true else false
      } else {
        false
      }
    } else {
      true
    }
  }

  override def next(): NetezzaRow = {
    val row = recordParser.parse(nextLine)
    // read the next line in advance to check if there is more data.
    nextLine = getNextLine()
    row
  }

  /**
   * Returns next line in the pipe. If an exception occured during execution, it
   * is thrown to call to propagate to the user..
   */
  private def getNextLine(): String = {
    if (execThread.hasExceptionrOccured) {
      close()
      throw new RuntimeException(
        "Error creating external table pipe:" + execThread.exception.toString)
    } else {
      escapedReadLine()
    }
  }

  /**
    * Reads a line (CR, LF, CRLF are recognized as new line) of data from the input stream after
    * skipping escaped characters including new line characters in the data. EscapeChar option is
    * set by default on the external table created by this data source. The character immediately
    * following the '\'value is escaped. The only supported escape char value is '\'.
    */
  private def escapedReadLine(): String = {
    sb.clear()
    var prevChar = -1
    var eol = false
    var c = input.read()
    if (skipLF) {
      if (c == '\n') {
        c = input.read()
      }
      skipLF = false
    }
    // keep the code in the loop simple.
    while (c != -1 && !eol) {
      if (c == '\r' || c == '\n') {
        if (prevChar == '\\') {
          // escaped new line char include it in the returned string
          sb.append(c.toChar)
        } else {
          eol = true
          if (c == '\r') {
            skipLF = true;
          }
        }
      } else {
        sb.append(c.toChar)
      }
      if (!eol) {
        if (c == '\\' && prevChar == '\\') {
          // don't set escaped escape character as previous char, otherwise if there
          // is CRLF after that it will be considered as escape incorrectly.
          prevChar = -1
        } else {
          prevChar = c
        }
        c = input.read()
      }
    }

    if (sb.length > 0) {
      sb.toString()
    } else {
      // end of stream
      null
    }
  }

  def close(): Unit = {
    if (!closed) {
      execThread.receivedEarlyOut = !execThread.hasExceptionrOccured
      stmt.close()
      closePipe()
      closeInputStream()
      execThread.join()
      closed = true
    }
  }

  private def closeInputStream() {
    log.info("close input stream ");
    if (fis != null) {
      fis.close();
      fis = null;
    }
    if (isr != null) {
      isr.close();
      isr = null;
    }
    if (input != null) {
      input.close();
      input = null;
    }
  }

  private def closePipe() {
    log.info("close pipe ");
    if (pipe != null && pipe.exists()) {
      pipe.delete();
    }
  }
}
