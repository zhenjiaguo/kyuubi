/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.api.v1

import javax.ws.rs._
import javax.ws.rs.core.{MediaType, Response}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import io.swagger.v3.oas.annotations.media.{ArraySchema, Content, Schema}
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.apache.hive.service.rpc.thrift.{TGetInfoType, TProtocolVersion}

import org.apache.kyuubi.Logging
import org.apache.kyuubi.client.api.v1.dto._
import org.apache.kyuubi.events.KyuubiEvent
import org.apache.kyuubi.operation.OperationHandle
import org.apache.kyuubi.server.api.ApiRequestContext
import org.apache.kyuubi.server.http.authentication.AuthenticationFilter
import org.apache.kyuubi.session.KyuubiSession
import org.apache.kyuubi.session.SessionHandle.parseSessionHandle

@Tag(name = "Session")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class SessionsResource extends ApiRequestContext with Logging {

  private def sessionManager = fe.be.sessionManager

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      array = new ArraySchema(schema = new Schema(implementation = classOf[SessionData])))),
    description = "get the list of all live sessions")
  @GET
  def sessions(): Seq[SessionData] = {
    sessionManager.allSessions().map { session =>
      new SessionData(
        new SessionHandle(
          session.handle.identifier.publicId,
          session.handle.identifier.secretId,
          session.handle.protocol.getValue),
        session.user,
        session.ipAddress,
        session.conf.asJava,
        session.createTime,
        session.lastAccessTime - session.createTime,
        session.getNoOperationTime)
    }.toSeq
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[KyuubiEvent]))),
    description = "get a session event via session handle identifier")
  @GET
  @Path("{sessionHandle}")
  def sessionInfo(@PathParam("sessionHandle") sessionHandleStr: String): KyuubiEvent = {
    try {
      fe.be.sessionManager.getSession(parseSessionHandle(sessionHandleStr))
        .asInstanceOf[KyuubiSession].getSessionEvent.get
    } catch {
      case NonFatal(e) =>
        error(s"Invalid $sessionHandleStr", e)
        throw new NotFoundException(s"Invalid $sessionHandleStr")
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[InfoDetail]))),
    description =
      "get a information detail via session handle identifier and a specific information type")
  @GET
  @Path("{sessionHandle}/info/{infoType}")
  def getInfo(
      @PathParam("sessionHandle") sessionHandleStr: String,
      @PathParam("infoType") infoType: Int): InfoDetail = {
    try {
      val info = TGetInfoType.findByValue(infoType)
      val infoValue = fe.be.getInfo(parseSessionHandle(sessionHandleStr), info)
      new InfoDetail(info.toString, infoValue.getStringValue)
    } catch {
      case NonFatal(e) =>
        error(s"Unrecognized GetInfoType value: $infoType", e)
        throw new NotFoundException(s"Unrecognized GetInfoType value: $infoType")
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[SessionOpenCount]))),
    description = "Get the current open session count")
  @GET
  @Path("count")
  def sessionCount(): SessionOpenCount = {
    new SessionOpenCount(sessionManager.getOpenSessionCount)
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[ExecPoolStatistic]))),
    description = "Get statistic info of background executors")
  @GET
  @Path("execPool/statistic")
  def execPoolStatistic(): ExecPoolStatistic = {
    new ExecPoolStatistic(
      sessionManager.getExecPoolSize,
      sessionManager.getActiveCount)
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[SessionHandle]))),
    description = "Open(create) a session")
  @POST
  @Consumes(Array(MediaType.APPLICATION_JSON))
  def openSession(request: SessionOpenRequest): SessionHandle = {
    val userName = fe.getUserName(request.getConfigs.asScala.toMap)
    val ipAddress = AuthenticationFilter.getUserIpAddress
    val handle = fe.be.openSession(
      TProtocolVersion.findByValue(request.getProtocolVersion),
      userName,
      request.getPassword,
      ipAddress,
      request.getConfigs.asScala.toMap)
    new SessionHandle(
      handle.identifier.publicId,
      handle.identifier.secretId,
      handle.protocol.getValue)
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON)),
    description = "Close a session")
  @DELETE
  @Path("{sessionHandle}")
  def closeSession(@PathParam("sessionHandle") sessionHandleStr: String): Response = {
    fe.be.closeSession(parseSessionHandle(sessionHandleStr))
    Response.ok().build()
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with EXECUTE_STATEMENT type")
  @POST
  @Path("{sessionHandle}/operations/statement")
  def executeStatement(
      @PathParam("sessionHandle") sessionHandleStr: String,
      request: StatementRequest): OperationHandle = {
    try {
      fe.be.executeStatement(
        parseSessionHandle(sessionHandleStr),
        request.getStatement,
        Map.empty,
        request.isRunAsync,
        request.getQueryTimeout)
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error executing statement"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with GET_TYPE_INFO type")
  @POST
  @Path("{sessionHandle}/operations/typeInfo")
  def getTypeInfo(@PathParam("sessionHandle") sessionHandleStr: String): OperationHandle = {
    try {
      fe.be.getTypeInfo(parseSessionHandle(sessionHandleStr))
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error getting type information"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with GET_CATALOGS type")
  @POST
  @Path("{sessionHandle}/operations/catalogs")
  def getCatalogs(@PathParam("sessionHandle") sessionHandleStr: String): OperationHandle = {
    try {
      fe.be.getCatalogs(parseSessionHandle(sessionHandleStr))
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error getting catalogs"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with GET_SCHEMAS type")
  @POST
  @Path("{sessionHandle}/operations/schemas")
  def getSchemas(
      @PathParam("sessionHandle") sessionHandleStr: String,
      request: GetSchemasRequest): OperationHandle = {
    try {
      val sessionHandle = parseSessionHandle(sessionHandleStr)
      val operationHandle = fe.be.getSchemas(
        sessionHandle,
        request.getCatalogName,
        request.getSchemaName)
      operationHandle
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error getting schemas"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with GET_TABLES type")
  @POST
  @Path("{sessionHandle}/operations/tables")
  def getTables(
      @PathParam("sessionHandle") sessionHandleStr: String,
      request: GetTablesRequest): OperationHandle = {
    try {
      fe.be.getTables(
        parseSessionHandle(sessionHandleStr),
        request.getCatalogName,
        request.getSchemaName,
        request.getTableName,
        request.getTableTypes)
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error getting tables"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with GET_TABLE_TYPES type")
  @POST
  @Path("{sessionHandle}/operations/tableTypes")
  def getTableTypes(@PathParam("sessionHandle") sessionHandleStr: String): OperationHandle = {
    try {
      fe.be.getTableTypes(parseSessionHandle(sessionHandleStr))
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error getting table types"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with GET_COLUMNS type")
  @POST
  @Path("{sessionHandle}/operations/columns")
  def getColumns(
      @PathParam("sessionHandle") sessionHandleStr: String,
      request: GetColumnsRequest): OperationHandle = {
    try {
      fe.be.getColumns(
        parseSessionHandle(sessionHandleStr),
        request.getCatalogName,
        request.getSchemaName,
        request.getTableName,
        request.getColumnName)
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error getting columns"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with GET_FUNCTIONS type")
  @POST
  @Path("{sessionHandle}/operations/functions")
  def getFunctions(
      @PathParam("sessionHandle") sessionHandleStr: String,
      request: GetFunctionsRequest): OperationHandle = {
    try {
      fe.be.getFunctions(
        parseSessionHandle(sessionHandleStr),
        request.getCatalogName,
        request.getSchemaName,
        request.getFunctionName)
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error getting functions"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with GET_FUNCTIONS type")
  @POST
  @Path("{sessionHandle}/operations/primaryKeys")
  def getPrimaryKeys(
      @PathParam("sessionHandle") sessionHandleStr: String,
      request: GetPrimaryKeysRequest): OperationHandle = {
    try {
      fe.be.getPrimaryKeys(
        parseSessionHandle(sessionHandleStr),
        request.getCatalogName,
        request.getSchemaName,
        request.getTableName)
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error getting primary keys"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(
      mediaType = MediaType.APPLICATION_JSON,
      schema = new Schema(implementation = classOf[OperationHandle]))),
    description = "Create an operation with GET_FUNCTIONS type")
  @POST
  @Path("{sessionHandle}/operations/crossReference")
  def getCrossReference(
      @PathParam("sessionHandle") sessionHandleStr: String,
      request: GetCrossReferenceRequest): OperationHandle = {
    try {
      fe.be.getCrossReference(
        parseSessionHandle(sessionHandleStr),
        request.getPrimaryCatalog,
        request.getPrimarySchema,
        request.getPrimaryTable,
        request.getForeignCatalog,
        request.getForeignSchema,
        request.getForeignTable)
    } catch {
      case NonFatal(e) =>
        val errorMsg = "Error getting cross reference"
        error(errorMsg, e)
        throw new NotFoundException(errorMsg)
    }
  }
}
