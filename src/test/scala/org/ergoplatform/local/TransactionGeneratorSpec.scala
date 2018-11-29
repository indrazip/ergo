package org.ergoplatform.local

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import org.ergoplatform.P2PKAddress
import org.ergoplatform.local.ErgoMiner.StartMining
import org.ergoplatform.local.TransactionGenerator.StartGeneration
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.nodeView.ErgoReadersHolder.{GetReaders, Readers}
import org.ergoplatform.nodeView.state.StateType
import org.ergoplatform.nodeView.wallet.ErgoWalletReader
import org.ergoplatform.nodeView.{ErgoNodeViewRef, ErgoReadersHolderRef}
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.utils.{ErgoTestHelpers, WalletTestOps}
import org.scalatest.FlatSpec
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.SuccessfulTransaction

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class TransactionGeneratorSpec extends FlatSpec with ErgoTestHelpers with WalletTestOps {

  implicit private val timeout: Timeout = defaultTimeout
  type MsgType = SuccessfulTransaction[_]
  val newTransaction: Class[MsgType] = classOf[MsgType]

  val transactionAwaitDuration: FiniteDuration = 30.seconds

  val defaultSettings: ErgoSettings = {
    val empty = ErgoSettings.read(None)

    val nodeSettings = empty.nodeSettings.copy(
      mining = true,
      stateType = StateType.Utxo,
      miningDelay = 1.second,
      offlineGeneration = true,
      verifyTransactions = true
    )
    val chainSettings = empty.chainSettings.copy(blockInterval = 1.seconds)
    empty.copy(nodeSettings = nodeSettings, chainSettings = chainSettings)
  }

  def containsAssetIssuingBox(tx: ErgoTransaction): Boolean = {
    val firstInputId = tx.inputs.head.boxId
    val assetIds = tx.outAssetsTry.get.map(_._1.data).toSeq
    assetIds.exists(x => java.util.Arrays.equals(x, firstInputId))
  }

  it should "generate valid transactions of all types" in new TestKit(ActorSystem()) {

    val ergoSettings: ErgoSettings = defaultSettings.copy(directory = createTempDir.getAbsolutePath)

    system.eventStream.subscribe(testActor, newTransaction)

    val nodeViewHolderRef: ActorRef = ErgoNodeViewRef(ergoSettings, timeProvider)
    val readersHolderRef: ActorRef = ErgoReadersHolderRef(nodeViewHolderRef)
    expectNoMessage(1.second)

    val minerRef: ActorRef = ErgoMinerRef(
      ergoSettings,
      nodeViewHolderRef,
      readersHolderRef,
      timeProvider,
      Some(defaultMinerSecret)
    )
    minerRef ! StartMining

    val txGenRef = TransactionGeneratorRef(nodeViewHolderRef, ergoSettings)
    txGenRef ! StartGeneration

    val ergoTransferringTx: Boolean = fishForSpecificMessage(transactionAwaitDuration) {
      case SuccessfulTransaction(tx: ErgoTransaction)
        if tx.outAssetsTry.get.isEmpty && !containsAssetIssuingBox(tx) => true
    }

    val tokenTransferringTx: Boolean = fishForSpecificMessage(transactionAwaitDuration) {
      case SuccessfulTransaction(tx: ErgoTransaction)
        if tx.outAssetsTry.get.nonEmpty && !containsAssetIssuingBox(tx) => true
    }

    val tokenIssuingTx: Boolean = fishForSpecificMessage(transactionAwaitDuration) {
      case SuccessfulTransaction(tx: ErgoTransaction) if containsAssetIssuingBox(tx) => true
    }

    ergoTransferringTx shouldBe true
    tokenTransferringTx shouldBe true
    tokenIssuingTx shouldBe true
  }

}
