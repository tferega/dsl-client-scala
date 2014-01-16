package com.dslplatform.api.client

import java.io.IOException
import java.util.concurrent.Executors
import com.fasterxml.jackson.databind.JavaType
import scala.concurrent._
import java.net.URLEncoder
import scala.reflect._
import com.dslplatform.api.patterns.ServiceLocator
import org.slf4j.Logger
import com.ning.http.util.Base64
import com.ning.http.client.Request
import com.ning.http.client.RequestBuilder
import com.ning.http.client.AsyncHandler
import com.ning.http.client.AsyncCompletionHandler
import com.ning.http.client.Response
import com.ning.http.client.AsyncHttpClient
import com.ning.http.client.AsyncHttpClientConfig
import scala.collection.JavaConversions._
import com.dslplatform.api.patterns.Identifiable

object HttpClientUtil {

  def encode(uri: String) = java.net.URLEncoder.encode(uri, "UTF-8")

  implicit class implicitStringSlashString(str: String) {
    def /(sec: String) = str + "/" + sec
  }

  trait HttpMethod { def name = toString }

  trait NoBodyRequest extends HttpMethod

  trait WithBody[TArg] extends HttpMethod {
    def arg: TArg
    def body(implicit json: JsonSerialization): Array[Byte] = json.serialize(arg).getBytes()
  }

  case object GET extends NoBodyRequest
  case object DELETE extends NoBodyRequest
  case class POST[TArg](arg: TArg) extends WithBody[TArg] {
    override val name = "POST"
  }
  case class PUT[TArg](arg: TArg) extends WithBody[TArg] {
    override val name = "PUT"
  }
}

class HttpClient(
    locator: ServiceLocator,
    projectSettings: ProjectSettings,
    json: JsonSerialization,
    logger: Logger,
    executorService: java.util.concurrent.ExecutorService) {

  import HttpClientUtil._
  private val remoteUrl = projectSettings.get("api-url")
  private val domainPrefix = projectSettings.get("package-name")
  private val domainPrefixLength = domainPrefix.length() + 1
  private val token = projectSettings.get("username") + ':' + projectSettings.get("project-id");
  private val basicAuth = "Basic " + new String(Base64.encode(token.getBytes("UTF-8")))
  private val MIME_TYPE = "application/json"
  private implicit val ec = ExecutionContext.fromExecutorService(executorService)
  private val commonHeaders = Map(
    "Accept" -> Set(MIME_TYPE),
    "Content-Type" -> Set(MIME_TYPE),
    "Authorization" -> Set(basicAuth))

  private[client] def getDslName[T: ClassTag] =
    classTag[T].runtimeClass.getName.substring(domainPrefixLength).replace("$", "")

  private[client] def getDslName(clazz: Class[_]) =
    clazz.getName.substring(domainPrefixLength).replace("$", "")

  private val configBuilder = new AsyncHttpClientConfig.Builder()
  configBuilder.setExecutorService(executorService)
  private val config = configBuilder.build()
  private val ahc = new AsyncHttpClient(config)
  // ---------------------------

  if (logger.isDebugEnabled()) {
    logger.debug("Initialized with: \n    username [{}] \n    api: [{}] \n    pid: [{}]", projectSettings.get("username"), projectSettings.get("api-url"), projectSettings.get("project-id"));
  }

  private def makeNingHeaders(additionalHeaders: Map[String, Set[String]]): java.util.Map[String, java.util.Collection[String]] = {
    val headers = new java.util.HashMap[String, java.util.Collection[String]]()
    if (logger.isTraceEnabled())
      for (h <- commonHeaders ++ additionalHeaders) {
        logger.trace("Added header: %s:%s" format (h._1, h._2.mkString("[", ",", "]")))
        headers.put(h._1, asJavaCollection(h._2))
      }
    headers
  }

  private def httpResponseHandler(
    resp: Promise[Array[Byte]], expectedHeaders: Set[Int]) =
    new AsyncCompletionHandler[Unit] {

      def onCompleted(response: Response) {
        if (logger.isTraceEnabled()) logger.trace("Received response status[%s] body: %s" format (response.getStatusCode(), response.getResponseBody()))
        if (expectedHeaders contains response.getStatusCode())
          resp success response.getResponseBodyAsBytes()

        else
          resp failure new IOException(
            "Unexpected return code: "
              + response.getStatusCode()
              + ", response: "
              + response.getResponseBody())
      }

      override def onThrowable(t: Throwable) {
        resp failure t
      }
    }

  private def doRequest(
    method: String,
    optBody: Option[Array[Byte]],
    url: String,
    expectedStatus: Set[Int],
    additionalHeaders: Map[String, Set[String]]): Future[Array[Byte]] = {
    val request = new RequestBuilder()
      .setUrl(url)
      .setHeaders(makeNingHeaders(additionalHeaders))
      .setMethod(method)

    if (logger.isTraceEnabled()) logger.trace("Sending request %s [%s]" format (method, url))
    optBody.foreach {
      body =>
        logger.trace("payload: {}", new String(body, "UTF-8"))
        request.setBody(body)
    }

    val promisedResponse = Promise[Array[Byte]]

    ahc.executeRequest(request.build(),
      httpResponseHandler(promisedResponse, expectedStatus))

    promisedResponse future
  }

  private[client] def sendRawRequest(
    method: HttpMethod,
    service: String,
    expectedStatus: Set[Int],
    additionalHeaders: Map[String, Set[String]] = Map.empty): Future[Array[Byte]] = {

    val url = remoteUrl + service

    val optBody = method match {
      case wb: WithBody[_] => Some(wb.body(json))
      case _               => None
    }

    doRequest(method.name, optBody, url, expectedStatus, additionalHeaders)
  }

  private[client] def sendStandardRequest(
    method: HttpMethod,
    service: String,
    expectedStatus: Set[Int],
    additionalHeaders: Map[String, Set[String]] = Map.empty): Future[IndexedSeq[String]] = {

    val url = remoteUrl + service

    val optBody = method match {
      case wb: WithBody[_] => Some(wb.body(json))
      case _               => None
    }

    doRequest(method.name, optBody, url, expectedStatus, additionalHeaders) map (json.deserializeList[String](_))
  }

  private[client] def sendRequest[TResult: ClassTag](
    method: HttpMethod,
    service: String,
    expectedStatus: Set[Int],
    additionalHeaders: Map[String, Set[String]] = Map.empty): Future[TResult] =
    sendRawRequest(method, service, expectedStatus, additionalHeaders) map (json.deserialize[TResult](_))

  private[client] def sendRequestForCollection[TResult: ClassTag](
    method: HttpMethod,
    service: String,
    expectedStatus: Set[Int],
    additionalHeaders: Map[String, Set[String]] = Map.empty): Future[IndexedSeq[TResult]] =
    sendRawRequest(method, service, expectedStatus, additionalHeaders) map (json.deserializeList[TResult](_))

  private[client] def sendRequestForCollection[TResult](
    returnClass: Class[_],
    method: HttpMethod,
    service: String,
    expectedStatus: Set[Int],
    additionalHeaders: Map[String, Set[String]] = Map.empty): Future[IndexedSeq[TResult]] =
    sendRawRequest(method, service, expectedStatus, additionalHeaders) map (json.deserializeList(returnClass, _))
}
