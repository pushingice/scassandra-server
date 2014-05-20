package org.scassandra.server

import org.scalatest.{BeforeAndAfter, Matchers, FunSuite}
import akka.testkit.{TestActorRef, TestProbe, TestKitBase}
import org.scalatest.mock.MockitoSugar
import akka.actor.{ActorRef, ActorSystem}
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scassandra.cqlmessages.response._
import org.scassandra.cqlmessages._
import akka.util.ByteString
import org.scassandra.cqlmessages.request.{ExecuteRequest, PrepareRequest}
import org.scassandra.cqlmessages.response.PreparedResultV2
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import org.scassandra.priming.prepared.{PreparedPrime, PrimePreparedStore}
import org.scassandra.priming.query.{Prime, PrimeMatch}
import org.scassandra.cqlmessages.response.VoidResult
import org.scassandra.cqlmessages.response.PreparedResultV2
import org.scassandra.cqlmessages.request.ExecuteRequest
import scala.Some
import org.scassandra.cqlmessages.request.PrepareRequest
import org.scassandra.priming.prepared.PreparedPrime
import org.scassandra.priming.query.PrimeMatch
import org.scassandra.cqlmessages.response.Rows
import org.scassandra.priming._
import org.scassandra.cqlmessages.response.ReadRequestTimeout
import org.scassandra.cqlmessages.response.VoidResult
import org.scassandra.cqlmessages.response.PreparedResultV2
import org.scassandra.cqlmessages.request.ExecuteRequest
import scala.Some
import org.scassandra.cqlmessages.request.PrepareRequest
import org.scassandra.priming.prepared.PreparedPrime
import org.scassandra.cqlmessages.response.WriteRequestTimeout
import org.scassandra.priming.query.PrimeMatch
import org.scassandra.cqlmessages.response.UnavailableException
import org.scassandra.cqlmessages.response.Rows
import org.scassandra.priming.query.Prime

class PrepareHandlerTest extends FunSuite with Matchers with TestKitBase with BeforeAndAfter with MockitoSugar {
  implicit lazy val system = ActorSystem()

  var underTest: ActorRef = null
  var testProbeForTcpConnection: TestProbe = null
  val cqlMessageFactory = VersionTwoMessageFactory
  val protocolVersion: Byte = ProtocolVersion.ServerProtocolVersionTwo
  implicit val impProtocolVersion = VersionTwo
  val primePreparedStore = mock[PrimePreparedStore]
  val stream: Byte = 0x3

  before {
    reset(primePreparedStore)
    when(primePreparedStore.findPrime(any(classOf[PrimeMatch]))).thenReturn(None)
    testProbeForTcpConnection = TestProbe()
    underTest = TestActorRef(new PrepareHandler(primePreparedStore))
  }


  test("Should return result prepared message - no params") {
    val stream: Byte = 0x02
    val query = "select * from something"
    val prepareBody: ByteString = PrepareRequest(protocolVersion, stream, query).serialize().drop(8)

    underTest ! PrepareHandlerMessages.Prepare(prepareBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    testProbeForTcpConnection.expectMsg(PreparedResultV2(stream, 1.toShort, "keyspace", "table", List()))
  }

  test("Should return empty result message for execute - no params") {
    val stream: Byte = 0x02
    val query = "select * from something"
    val executeBody: ByteString = ExecuteRequest(protocolVersion, stream, 1).serialize().drop(8);

    underTest ! PrepareHandlerMessages.Execute(executeBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    testProbeForTcpConnection.expectMsg(VoidResult(stream))
  }

  test("Should return result prepared message - single param") {
    val stream: Byte = 0x02
    val query = "select * from something where name = ?"
    val prepareBody: ByteString = PrepareRequest(protocolVersion, stream, query).serialize().drop(8)

    underTest ! PrepareHandlerMessages.Prepare(prepareBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    testProbeForTcpConnection.expectMsg(PreparedResultV2(stream, 1.toShort, "keyspace", "table", List(CqlVarchar)))
  }

  test("Priming variable types - Should use types from PreparedPrime") {
    val query = "select * from something where name = ?"
    val prepareBody: ByteString = PrepareRequest(protocolVersion, stream, query).serialize().drop(8)
    val primedVariableTypes = List(CqlInt)
    val preparedPrime: PreparedPrime = PreparedPrime(primedVariableTypes)
    when(primePreparedStore.findPrime(any(classOf[PrimeMatch]))).thenReturn(Some(preparedPrime))

    underTest ! PrepareHandlerMessages.Prepare(prepareBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    verify(primePreparedStore).findPrime(PrimeMatch(query))
    testProbeForTcpConnection.expectMsg(PreparedResultV2(stream, 1.toShort, "keyspace", "table", primedVariableTypes))
  }
  
  test("Should use incrementing IDs") {
    underTest = TestActorRef(new PrepareHandler(primePreparedStore))
    val stream: Byte = 0x02
    val queryOne = "select * from something where name = ?"
    val prepareBodyOne: ByteString = PrepareRequest(protocolVersion, stream, queryOne).serialize().drop(8)
    underTest ! PrepareHandlerMessages.Prepare(prepareBodyOne, stream, cqlMessageFactory, testProbeForTcpConnection.ref)
    testProbeForTcpConnection.expectMsg(PreparedResultV2(stream, 1.toShort, "keyspace", "table", List(CqlVarchar)))

    emptyTestProbe

    val queryTwo = "select * from something where name = ? and age = ?"
    val prepareBodyTwo: ByteString = PrepareRequest(protocolVersion, stream, queryTwo).serialize().drop(8)
    underTest ! PrepareHandlerMessages.Prepare(prepareBodyTwo, stream, cqlMessageFactory, testProbeForTcpConnection.ref)
    testProbeForTcpConnection.expectMsg(PreparedResultV2(stream, 2.toShort, "keyspace", "table", List(CqlVarchar, CqlVarchar)))

  }

  test("Should look up prepared prime in store with consistency & query") {
    val stream: Byte = 0x02
    val query = "select * from something where name = ?"
    val preparedStatementId = sendPrepareAndCaptureId(stream, query)
    val consistency = THREE

    val executeBody: ByteString = ExecuteRequest(protocolVersion, stream, preparedStatementId, consistency).serialize().drop(8);
    underTest ! PrepareHandlerMessages.Execute(executeBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    verify(primePreparedStore).findPrime(PrimeMatch(query, consistency))
  }

  test("Should create rows message if prime matches") {
    val stream: Byte = 0x02
    val query = "select * from something where name = ?"
    val preparedStatementId = sendPrepareAndCaptureId(stream, query)
    val primeMatch = Some(PreparedPrime())
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequest(protocolVersion, stream, preparedStatementId).serialize().drop(8);
    underTest ! PrepareHandlerMessages.Execute(executeBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    testProbeForTcpConnection.expectMsg(Rows("" ,"" ,stream, Map(), List()))
  }

  test("Execute with read time out") {
    val query = "select * from something where name = ?"
    val preparedStatementId = sendPrepareAndCaptureId(stream, query)
    val primeMatch = Some(PreparedPrime(prime = Prime(result = ReadTimeout)))
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequest(protocolVersion, stream, preparedStatementId).serialize().drop(8);
    underTest ! PrepareHandlerMessages.Execute(executeBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    testProbeForTcpConnection.expectMsg(ReadRequestTimeout(stream))
  }

  test("Execute with write time out") {
    val query = "select * from something where name = ?"
    val preparedStatementId = sendPrepareAndCaptureId(stream, query)
    val primeMatch = Some(PreparedPrime(prime = Prime(result = WriteTimeout)))
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequest(protocolVersion, stream, preparedStatementId).serialize().drop(8);
    underTest ! PrepareHandlerMessages.Execute(executeBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    testProbeForTcpConnection.expectMsg(WriteRequestTimeout(stream))
  }

  test("Execute with unavailable") {
    val query = "select * from something where name = ?"
    val preparedStatementId = sendPrepareAndCaptureId(stream, query)
    val primeMatch = Some(PreparedPrime(prime = Prime(result = Unavailable)))
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequest(protocolVersion, stream, preparedStatementId).serialize().drop(8);
    underTest ! PrepareHandlerMessages.Execute(executeBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    testProbeForTcpConnection.expectMsg(UnavailableException(stream))
  }

  test("Should record execution in activity log") {
    ActivityLog.clearPreparedStatementExecutions()
    val stream: Byte = 0x02
    val query = "select * from something where name = ?"
    val preparedStatementId = sendPrepareAndCaptureId(stream, query)
    val consistency = TWO
    val primeMatch = Some(PreparedPrime())
    when(primePreparedStore.findPrime(any[PrimeMatch])).thenReturn(primeMatch)

    val executeBody: ByteString = ExecuteRequest(protocolVersion, stream, preparedStatementId, consistency).serialize().drop(8);
    underTest ! PrepareHandlerMessages.Execute(executeBody, stream, cqlMessageFactory, testProbeForTcpConnection.ref)

    ActivityLog.retrievePreparedStatementExecutions().size should equal(1)
    ActivityLog.retrievePreparedStatementExecutions()(0) should equal(PreparedStatementExecution(query, consistency, List()))
  }


  private def sendPrepareAndCaptureId(stream: Byte, query: String) : Int = {
    val prepareBodyOne: ByteString = PrepareRequest(protocolVersion, stream, query).serialize().drop(8)
    underTest ! PrepareHandlerMessages.Prepare(prepareBodyOne, stream, cqlMessageFactory , testProbeForTcpConnection.ref)
    val preparedResponseWithId: PreparedResultV2 = testProbeForTcpConnection.expectMsg(PreparedResultV2(stream, 1.toShort, "keyspace", "table", List(CqlVarchar)))
    reset(primePreparedStore)
    when(primePreparedStore.findPrime(any(classOf[PrimeMatch]))).thenReturn(None)
    preparedResponseWithId.preparedStatementId
  }

  private def emptyTestProbe = {
    testProbeForTcpConnection.receiveWhile(idle = Duration(100, TimeUnit.MILLISECONDS))({
      case msg @ _ => println(s"Removing message from test probe ${msg}")
    })
  }
}