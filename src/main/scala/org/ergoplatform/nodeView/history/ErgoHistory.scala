package org.ergoplatform.nodeView.history

import java.io.File

import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import org.ergoplatform.modifiers.history._
import org.ergoplatform.modifiers.mempool.AnyoneCanSpendTransaction
import org.ergoplatform.modifiers.mempool.proposition.AnyoneCanSpendProposition
import org.ergoplatform.modifiers.state.UTXOSnapshotChunk
import org.ergoplatform.modifiers.{ErgoFullBlock, ErgoPersistentModifier}
import org.ergoplatform.nodeView.history.storage._
import org.ergoplatform.nodeView.history.storage.modifierprocessors._
import org.ergoplatform.nodeView.history.storage.modifierprocessors.adproofs.{ADProofsProcessor, ADStateProofsProcessor, EmptyADProofsProcessor}
import org.ergoplatform.nodeView.history.storage.modifierprocessors.blocktransactions.{BlockTransactionsProcessor, EmptyBlockTransactionsProcessor, FullnodeBlockTransactionsProcessor}
import org.ergoplatform.nodeView.history.storage.modifierprocessors.popow.{EmptyPoPoWProofsProcessor, FullPoPoWProofsProcessor, PoPoWProofsProcessor}
import org.ergoplatform.settings.{Algos, ErgoSettings}
import scorex.core.NodeViewModifier._
import scorex.core.consensus.History
import scorex.core.consensus.History.{HistoryComparisonResult, ModifierIds, ProgressInfo}
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58

import scala.annotation.tailrec
import scala.util.{Failure, Try}

//TODO replace ErgoPersistentModifier to HistoryModifier
trait ErgoHistory
  extends History[AnyoneCanSpendProposition, AnyoneCanSpendTransaction, ErgoPersistentModifier, ErgoSyncInfo, ErgoHistory]
    with HeadersProcessor
    with ADProofsProcessor
    with PoPoWProofsProcessor
    with BlockTransactionsProcessor
    with ScorexLogging {

  protected val config: HistoryConfig
  protected val storage: LSMStore
  //TODO what should be the limit?
  val MaxRollback = 10000

  lazy val historyStorage: HistoryStorage = new HistoryStorage(storage)

  def isEmpty: Boolean = bestHeaderIdOpt.isEmpty


  //It is safe to call this function right after history initialization with genesis block
  def bestHeader: Header = bestHeaderOpt.get

  //None for light mode, Some for fullnode regime after initial bootstrap
  def bestFullBlockOpt: Option[ErgoFullBlock] = Try {
    getFullBlock(typedModifierById[Header](bestFullBlockIdOpt.get).get)
  }.toOption

  protected def getFullBlock(header: Header): ErgoFullBlock = {
    val aDProofs = typedModifierById[ADProof](header.ADProofsId)
    val txs = typedModifierById[BlockTransactions](header.transactionsId).get
    ErgoFullBlock(header, txs, aDProofs, None)
  }

  def bestHeaderOpt: Option[Header] = bestHeaderIdOpt.flatMap(typedModifierById[Header])

  override def modifierById(id: ModifierId): Option[ErgoPersistentModifier] = historyStorage.modifierById(id)

  def typedModifierById[T <: ErgoPersistentModifier](id: ModifierId): Option[T] = modifierById(id) match {
    case Some(m: T) => Some(m)
    case _ => None
  }

  override def append(modifier: ErgoPersistentModifier): Try[(ErgoHistory, ProgressInfo[ErgoPersistentModifier])] = Try {
    log.debug(s"Trying to append modifier ${Base58.encode(modifier.id)} to history")
    applicableTry(modifier).get
    modifier match {
      case m: Header =>
        val dataToInsert = toInsert(m)
        historyStorage.insert(m.id, dataToInsert)
        if (isEmpty || (bestHeaderIdOpt.get sameElements m.id)) {
          log.info(s"New best header ${m.encodedId}")
          //TODO Notify node view holder that it should download transactions ?
          (this, ProgressInfo(None, Seq(), Seq()))
        } else {
          log.info(s"New orphaned header ${m.encodedId}, best: ${}")
          (this, ProgressInfo(None, Seq(), Seq()))
        }
      case m: BlockTransactions =>
        (this, process(m))
      case m: ADProof =>
        (this, process(m))
      case m: PoPoWProof =>
        (this, process(m))
      case m: UTXOSnapshotChunk =>
        //add mark that snapshot was applied
        ???
    }
  }

  /**
    * Constructs SPV Proof from KLS16 paper
    *
    * @param m - parameter "m" from the paper (minimal length of innerchain to include)
    * @param k - parameter "k" from the paper (chain suffix)
    * @return
    */
  def constructPoPoWProof(m: Int, k: Int): Try[PoPoWProof] = Try {
    val currentHeight = height
    require(m > 0 && m < currentHeight, s"$m > 0 && $m < $currentHeight")
    require(k > 0 && k < currentHeight, s"$k > 0 && $k < $currentHeight")

    val suffix: HeaderChain = lastHeaders(k)
    val firstSuffix = suffix.head


    def headerById(id: Array[Byte]): Header = typedModifierById[Header](id).get

    @tailrec
    def constructProof(i: Int): (Int, Seq[Header]) = {
      @tailrec
      def loop(acc: Seq[Header]): Seq[Header] = {
        val interHeader = acc.head
        if (interHeader.interlinks.length > i) {
          val header = headerById(interHeader.interlinks(i))
          loop(header +: acc)
        } else {
          acc.reverse.tail.reverse
        }
      }

      val innerchain = loop(Seq(firstSuffix))
      if (innerchain.length >= m) (i, innerchain) else constructProof(i - 1)
    }

    val (depth, innerchain) = constructProof(firstSuffix.interlinks.length)

    PoPoWProof(m.toByte, k.toByte, depth.toByte, innerchain, suffix.headers)
  }


  override def reportInvalid(modifier: ErgoPersistentModifier): ErgoHistory = {
    val (idsToRemove: Seq[ByteArrayWrapper], toInsert: Seq[(ByteArrayWrapper, ByteArrayWrapper)]) = modifier match {
      case h: Header => toDrop(h)
      case proof: ADProof => typedModifierById[Header](proof.headerId).map(h => toDrop(h)).getOrElse(Seq())
      case txs: BlockTransactions => typedModifierById[Header](txs.headerId).map(h => toDrop(h)).getOrElse(Seq())
      case m =>
        log.warn(s"reportInvalid for invalid modifier type: $m")
        (Seq(ByteArrayWrapper(m.id)), Seq())
    }

    historyStorage.update(Algos.hash(modifier.id ++ "reportInvalid".getBytes), idsToRemove, toInsert)
    this
  }

  override def openSurfaceIds(): Seq[ModifierId] = bestFullBlockIdOpt.orElse(bestHeaderIdOpt).toSeq

  override def applicable(modifier: ErgoPersistentModifier): Boolean = applicableTry(modifier).isSuccess

  def applicableTry(modifier: ErgoPersistentModifier): Try[Unit] = {
    modifier match {
      case m: Header =>
        validate(m)
      case m: BlockTransactions =>
        validate(m)
      case m: ADProof =>
        validate(m)
      case m: PoPoWProof =>
        validate(m)
      case m: UTXOSnapshotChunk =>
        Failure(new NotImplementedError)
      case m =>
        Failure(new Error(s"Modifier $m have incorrect type"))
    }
  }

  override def compare(info: ErgoSyncInfo): HistoryComparisonResult.Value = {
    //TODO check that work done is correct
    bestHeaderIdOpt match {
      case Some(id) if info.lastHeaderIds.lastOption.exists(_ sameElements id) =>
        //Header chain is equals, compare full blocks
        (info.fullBlockIdOpt, bestFullBlockIdOpt) match {
          case (Some(theirBestFull), Some(ourBestFull)) if !(theirBestFull sameElements ourBestFull) =>
            if (scoreOf(theirBestFull).exists(theirScore => scoreOf(ourBestFull).exists(_ > theirScore))) {
              HistoryComparisonResult.Younger
            } else {
              HistoryComparisonResult.Older
            }
          case _ =>
            HistoryComparisonResult.Equal
        }
      case Some(id) if info.lastHeaderIds.exists(_ sameElements id) =>
        HistoryComparisonResult.Older
      case Some(id) =>
        //Compare headers chain
        val ids = info.lastHeaderIds
        ids.view.reverse.find(m => contains(m)) match {
          case Some(lastId) =>
            val ourDiffOpt = heightOf(lastId).map(h => height - h)
            if (ourDiffOpt.exists(ourDiff => ourDiff > (ids.length - ids.indexWhere(_ sameElements lastId)))) {
              HistoryComparisonResult.Younger
            } else {
              HistoryComparisonResult.Older
            }
          case None => HistoryComparisonResult.Nonsense
        }
      case None =>
        log.warn("Trying to compare with other node while our history is empty")
        HistoryComparisonResult.Older
    }
  }

  override def continuationIds(info: ErgoSyncInfo, size: Int): Option[ModifierIds] = Try {
    val ids = info.lastHeaderIds
    val lastHeaderInHistory = ids.view.reverse.find(m => contains(m)).get
    val theirHeight = heightOf(lastHeaderInHistory).get
    val heightFrom = Math.min(height, theirHeight + size)
    val startId = headerIdsAtHeight(heightFrom).head
    val startHeader = typedModifierById[Header](startId).get
    val headerIds = headerChainBack(heightFrom - theirHeight, startHeader, (h: Header) => h.isGenesis)
      .headers.map(h => Header.ModifierTypeId -> h.id)
    val fullBlockContinuation: ModifierIds = info.fullBlockIdOpt.flatMap(heightOf) match {
      case Some(bestFullBlockHeight) =>
        val heightFrom = Math.min(height, bestFullBlockHeight + size)
        val startId = headerIdsAtHeight(heightFrom).head
        val startHeader = typedModifierById[Header](startId).get
        val headers = headerChainBack(heightFrom - bestFullBlockHeight, startHeader, (h: Header) => h.isGenesis)
        headers.headers.flatMap(h => Seq((ADProof.ModifierTypeId, h.ADProofsId),
          (BlockTransactions.ModifierTypeId, h.transactionsId)))
      case _ => Seq()
    }
    headerIds ++ fullBlockContinuation
  }.toOption

  override def syncInfo(answer: Boolean): ErgoSyncInfo = if (isEmpty) {
    ErgoSyncInfo(answer, Seq(), None)
  } else {
    ErgoSyncInfo(answer,
      lastHeaders(ErgoSyncInfo.MaxBlockIds).headers.map(_.id),
      bestFullBlockIdOpt)
  }

  protected[history] def commonBlockThenSuffixes(header1: Header, header2: Header): (HeaderChain, HeaderChain) = {
    assert(contains(header1))
    assert(contains(header2))
    def loop(numberBack: Int, otherChain: HeaderChain): (HeaderChain, HeaderChain) = {
      val r = commonBlockThenSuffixes(otherChain, header1, numberBack)
      if (r._1.head == r._2.head) {
        r
      } else if (numberBack < MaxRollback) {
        val biggerOther = headerChainBack(numberBack, otherChain.head, (h: Header) => h.isGenesis) ++ otherChain.tail
        loop(biggerOther.size, biggerOther)
      } else {
        throw new Error(s"Common point not found for headers $header1 and $header2")
      }
    }
    loop(2, HeaderChain(Seq(header2)))
  }

  protected[history] def commonBlockThenSuffixes(otherChain: HeaderChain,
                                                 startHeader: Header,
                                                 limit: Int = MaxRollback): (HeaderChain, HeaderChain) = {
    def until(h: Header): Boolean = otherChain.exists(_.id sameElements h.id)
    val ourChain = headerChainBack(limit, startHeader, until)
    val commonBlock = ourChain.head
    val commonBlockThenSuffixes = otherChain.takeAfter(commonBlock)
    (ourChain, commonBlockThenSuffixes)
  }

  def lastHeaders(count: Int): HeaderChain = headerChainBack(count, bestHeader, b => b.isGenesis)

  private def headerChainBack(count: Int, startHeader: Header, until: Header => Boolean): HeaderChain = {
    @tailrec
    def loop(block: Header, acc: Seq[Header]): Seq[Header] = {
      if (until(block) || (acc.length == count)) {
        acc
      } else {
        modifierById(block.parentId) match {
          case Some(parent: Header) =>
            loop(parent, acc :+ parent)
          case _ =>
            log.warn(s"No parent header in history for block $block")
            acc
        }
      }
    }

    if (isEmpty || (count == 0)) HeaderChain(Seq())
    else HeaderChain(loop(startHeader, Seq(startHeader)).reverse)
  }

  override type NVCT = ErgoHistory

}

object ErgoHistory extends ScorexLogging {

  def readOrGenerate(settings: ErgoSettings): ErgoHistory = {
    val dataDir = settings.dataDir
    val iFile = new File(s"$dataDir/history")
    iFile.mkdirs()
    val db = new LSMStore(iFile, maxJournalEntryCount = 10000)

    val historyConfig = HistoryConfig(settings.blocksToKeep, settings.minimalSuffix)

    //TODO make easier?
    val history: ErgoHistory = (settings.ADState, settings.verifyTransactions, settings.poPoWBootstrap) match {
      case (true, true, true) =>
        new ErgoHistory with ADStateProofsProcessor
          with FullnodeBlockTransactionsProcessor
          with FullPoPoWProofsProcessor {
          override protected val config: HistoryConfig = historyConfig
          override protected val storage: LSMStore = db
        }
      case (true, true, false) =>
        new ErgoHistory with ADStateProofsProcessor
          with FullnodeBlockTransactionsProcessor
          with EmptyPoPoWProofsProcessor {
          override protected val config: HistoryConfig = historyConfig
          override protected val storage: LSMStore = db
        }
      case (false, true, true) =>
        new ErgoHistory with EmptyADProofsProcessor
          with FullnodeBlockTransactionsProcessor
          with FullPoPoWProofsProcessor {
          override protected val config: HistoryConfig = historyConfig
          override protected val storage: LSMStore = db
        }
      case (false, true, false) =>
        new ErgoHistory with EmptyADProofsProcessor
          with FullnodeBlockTransactionsProcessor
          with EmptyPoPoWProofsProcessor {
          override protected val config: HistoryConfig = historyConfig
          override protected val storage: LSMStore = db
        }
      case (true, false, true) =>
        new ErgoHistory with EmptyADProofsProcessor
          with EmptyBlockTransactionsProcessor
          with FullPoPoWProofsProcessor {
          override protected val config: HistoryConfig = historyConfig
          override protected val storage: LSMStore = db
        }
      case (true, false, false) =>
        new ErgoHistory with EmptyADProofsProcessor
          with EmptyBlockTransactionsProcessor
          with EmptyPoPoWProofsProcessor {
          override protected val config: HistoryConfig = historyConfig
          override protected val storage: LSMStore = db
        }

    }

    if (history.isEmpty) {
      log.info("Initialize empty history with genesis block")
      val genesis: ErgoFullBlock = ErgoFullBlock.genesis
      history.append(genesis.header).get._1
    } else {
      log.info("Initialize non-empty history ")
      history
    }
  }

}


