// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.core

import scala.annotation.tailrec

import org.alephium.flow.core.BlockFlowState.{BlockCache, TxStatus}
import org.alephium.flow.core.FlowUtils._
import org.alephium.flow.core.UtxoUtils.Asset
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALF, BlockHash, Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, UnlockScript}
import org.alephium.util.{AVector, TimeStamp, U256}

trait TxUtils { Self: FlowUtils =>

  // We call getUsableUtxosOnce multiple times until the resulted tx does not change
  // In this way, we can guarantee that no concurrent utxos operations are making trouble
  def getUsableUtxos(
      lockupScript: LockupScript.Asset
  ): IOResult[AVector[AssetOutputInfo]] = {
    @tailrec
    def iter(lastTryOpt: Option[AVector[AssetOutputInfo]]): IOResult[AVector[AssetOutputInfo]] = {
      getUsableUtxosOnce(lockupScript) match {
        case Right(utxos) =>
          lastTryOpt match {
            case Some(lastTry) if isSame(utxos, lastTry) => Right(utxos)
            case _                                       => iter(Some(utxos))
          }
        case Left(error) => Left(error)
      }
    }
    iter(None)
  }

  def getUsableUtxosOnce(
      lockupScript: LockupScript.Asset
  ): IOResult[AVector[AssetOutputInfo]] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))
    for {
      groupView <- getImmutableGroupViewIncludePool(groupIndex)
      outputs   <- groupView.getRelevantUtxos(lockupScript, maxUtxosToReadForTransfer)
    } yield {
      val currentTs = TimeStamp.now()
      outputs.filter(_.output.lockTime <= currentTs)
    }
  }

  // return the total balance, the locked balance, and the number of all utxos
  def getBalance(lockupScript: LockupScript.Asset): IOResult[(U256, U256, Int)] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))

    val currentTs = TimeStamp.now()

    getUTXOs(lockupScript).map { utxos =>
      val balance = utxos.fold(U256.Zero)(_ addUnsafe _.output.amount)
      val lockedBalance = utxos.fold(U256.Zero) { case (acc, utxo) =>
        if (utxo.output.lockTime > currentTs) acc addUnsafe utxo.output.amount else acc
      }
      (balance, lockedBalance, utxos.length)
    }
  }

  def getUTXOs(lockupScript: LockupScript.Asset): IOResult[AVector[AssetOutputInfo]] = {
    val groupIndex = lockupScript.groupIndex
    assume(brokerConfig.contains(groupIndex))

    getImmutableGroupViewIncludePool(groupIndex)
      .flatMap(_.getRelevantUtxos(lockupScript, Int.MaxValue))
  }

  def transfer(
      fromPublicKey: PublicKey,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      amount: U256,
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): IOResult[Either[String, UnsignedTransaction]] = {
    transfer(
      fromPublicKey,
      AVector(TxOutputInfo(toLockupScript, amount, AVector.empty, lockTimeOpt)),
      gasOpt,
      gasPrice
    )
  }

  def transfer(
      fromPublicKey: PublicKey,
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)
    transfer(fromLockupScript, fromUnlockScript, outputInfos, gasOpt, gasPrice)
  }

  def transfer(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): IOResult[Either[String, UnsignedTransaction]] = {
    checkBeforeTransfer(outputInfos, gasOpt) match {
      case Right(totalAmount) =>
        selectUTXOs(fromLockupScript, totalAmount, outputInfos.length, gasOpt, gasPrice).map {
          _.flatMap { selected =>
            transferUnsafe(
              fromLockupScript,
              fromUnlockScript,
              selected.assets,
              outputInfos,
              selected.gas,
              gasPrice
            )
          }
        }

      case Left(e) =>
        Right(Left(e))
    }
  }

  def transfer(
      fromPublicKey: PublicKey,
      utxos: AVector[Asset],
      outputInfos: AVector[TxOutputInfo],
      gas: GasBox,
      gasPrice: GasPrice
  ): Either[String, UnsignedTransaction] = {
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)

    for {
      _ <- checkBeforeTransfer(outputInfos, Some(gas))
      unsignedTx <- transferUnsafe(
        fromLockupScript,
        fromUnlockScript,
        utxos,
        outputInfos,
        gas,
        gasPrice
      )
    } yield unsignedTx
  }

  def transferUnsafe(
      fromLockupScript: LockupScript.Asset,
      fromUnlockScript: UnlockScript,
      utxos: AVector[Asset],
      outputInfos: AVector[TxOutputInfo],
      gas: GasBox,
      gasPrice: GasPrice
  ): Either[String, UnsignedTransaction] = {
    for {
      _ <- checkWithMaxTxInputNum(utxos)
      unsignedTx <- UnsignedTransaction
        .transfer(
          fromLockupScript,
          fromUnlockScript,
          utxos.map(asset => (asset.ref, asset.output)),
          outputInfos,
          gas,
          gasPrice
        )
    } yield unsignedTx
  }

  def sweepAll(
      fromPublicKey: PublicKey,
      toLockupScript: LockupScript.Asset,
      lockTimeOpt: Option[TimeStamp],
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): IOResult[Either[String, UnsignedTransaction]] = {
    val fromLockupScript = LockupScript.p2pkh(fromPublicKey)
    val fromUnlockScript = UnlockScript.p2pkh(fromPublicKey)

    getUsableUtxos(fromLockupScript).map { allUtxos =>
      val utxos = allUtxos.takeUpto(ALF.MaxTxInputNum) // sweep as much as we can
      for {
        _   <- checkWithMinimalGas(gasOpt, minimalGas)
        gas <- Right(gasOpt.getOrElse(UtxoUtils.estimateGas(utxos.length, 1)))
        totalAmount <- utxos.foldE(U256.Zero)(
          _ add _.output.amount toRight "Input amount overflow"
        )
        amount <- totalAmount.sub(gasPrice * gas).toRight("Not enough balance for gas fee")
        unsignedTx <- UnsignedTransaction
          .transfer(
            fromLockupScript,
            fromUnlockScript,
            utxos.map(asset => (asset.ref, asset.output)),
            // FIXME! take care of tokens
            AVector(TxOutputInfo(toLockupScript, amount, AVector.empty, lockTimeOpt)),
            gas,
            gasPrice
          )
      } yield unsignedTx
    }
  }

  def getTxStatus(txId: Hash, chainIndex: ChainIndex): IOResult[Option[TxStatus]] =
    IOUtils.tryExecute {
      assume(brokerConfig.contains(chainIndex.from))
      val chain = getBlockChain(chainIndex)
      chain.getTxStatusUnsafe(txId).flatMap { chainStatus =>
        val confirmations = chainStatus.confirmations
        if (chainIndex.isIntraGroup) {
          Some(TxStatus(chainStatus.index, confirmations, confirmations, confirmations))
        } else {
          val confirmHash = chainStatus.index.hash
          val fromGroupConfirmations =
            getFromGroupConfirmationsUnsafe(confirmHash, chainIndex)
          val toGroupConfirmations =
            getToGroupConfirmationsUnsafe(confirmHash, chainIndex)
          Some(
            TxStatus(chainStatus.index, confirmations, fromGroupConfirmations, toGroupConfirmations)
          )
        }
      }
    }

  def getFromGroupConfirmationsUnsafe(hash: BlockHash, chainIndex: ChainIndex): Int = {
    assume(ChainIndex.from(hash) == chainIndex)
    val targetChain   = getHeaderChain(chainIndex)
    val header        = targetChain.getBlockHeaderUnsafe(hash)
    val fromChain     = getHeaderChain(chainIndex.from, chainIndex.from)
    val fromTip       = getOutTip(header, chainIndex.from)
    val fromTipHeight = fromChain.getHeightUnsafe(fromTip)

    @tailrec
    def iter(height: Int): Option[Int] = {
      val hashes = fromChain.getHashesUnsafe(height)
      if (hashes.isEmpty) {
        None
      } else {
        val header   = fromChain.getBlockHeaderUnsafe(hashes.head)
        val chainDep = header.uncleHash(chainIndex.to)
        if (targetChain.isBeforeUnsafe(hash, chainDep)) Some(height) else iter(height + 1)
      }
    }

    iter(fromTipHeight + 1) match {
      case None => 0
      case Some(firstConfirmationHeight) =>
        fromChain.maxHeightUnsafe - firstConfirmationHeight + 1
    }
  }

  def getToGroupConfirmationsUnsafe(hash: BlockHash, chainIndex: ChainIndex): Int = {
    assume(ChainIndex.from(hash) == chainIndex)
    val header        = getBlockHeaderUnsafe(hash)
    val toChain       = getHeaderChain(chainIndex.to, chainIndex.to)
    val toGroupTip    = getGroupTip(header, chainIndex.to)
    val toGroupHeader = getBlockHeaderUnsafe(toGroupTip)
    val toTip         = getOutTip(toGroupHeader, chainIndex.to)
    val toTipHeight   = toChain.getHeightUnsafe(toTip)

    assume(ChainIndex.from(toTip) == ChainIndex(chainIndex.to, chainIndex.to))

    @tailrec
    def iter(height: Int): Option[Int] = {
      val hashes = toChain.getHashesUnsafe(height)
      if (hashes.isEmpty) {
        None
      } else {
        val header   = toChain.getBlockHeaderUnsafe(hashes.head)
        val chainDep = getGroupTip(header, chainIndex.from)
        if (isExtendingUnsafe(chainDep, hash)) Some(height) else iter(height + 1)
      }
    }

    if (header.isGenesis) {
      toChain.maxHeightUnsafe - ALF.GenesisHeight + 1
    } else {
      iter(toTipHeight + 1) match {
        case None => 0
        case Some(firstConfirmationHeight) =>
          toChain.maxHeightUnsafe - firstConfirmationHeight + 1
      }
    }
  }

  // return all the txs that are not valid
  def recheckInputs(
      groupIndex: GroupIndex,
      txs: AVector[TransactionTemplate]
  ): IOResult[AVector[TransactionTemplate]] = {
    for {
      groupView <- getImmutableGroupView(groupIndex)
      failedTxs <- txs.filterE(tx => groupView.getPreOutputs(tx.unsigned.inputs).map(_.isEmpty))
    } yield failedTxs
  }

  private def checkTotalAmount(
      outputInfos: AVector[TxOutputInfo]
  ): Either[String, U256] = {
    outputInfos.foldE(U256.Zero) { case (acc, outputInfo) =>
      acc.add(outputInfo.alfAmount).toRight("Amount overflow")
    }
  }

  private def checkWithMinimalGas(
      gasOpt: Option[GasBox],
      minimalGas: GasBox
  ): Either[String, Unit] = {
    gasOpt match {
      case None => Right(())
      case Some(gas) =>
        if (gas < minimalGas) Left(s"Invalid gas $gas, minimal $minimalGas") else Right(())
    }
  }

  private def checkWithMaxTxInputNum(assets: AVector[Asset]): Either[String, Unit] = {
    if (assets.length > ALF.MaxTxInputNum) {
      Left(s"Too many inputs for the transfer, consider to reduce the amount to send")
    } else {
      Right(())
    }
  }

  private def checkOutputInfos(
      outputInfos: AVector[TxOutputInfo]
  ): Either[String, Unit] = {
    if (outputInfos.isEmpty) {
      Left("Zero transaction outputs")
    } else {
      val groupIndexes = outputInfos.map(_.lockupScript.groupIndex)

      if (groupIndexes.forall(_ == groupIndexes.head)) {
        Right(())
      } else {
        Left("Different groups for transaction outputs")
      }
    }
  }

  private def checkBeforeTransfer(
      outputInfos: AVector[TxOutputInfo],
      gasOpt: Option[GasBox]
  ): Either[String, U256] = {
    for {
      totalAmount <- checkTotalAmount(outputInfos)
      _           <- checkOutputInfos(outputInfos)
      _           <- checkWithMinimalGas(gasOpt, minimalGas)
    } yield totalAmount
  }

  private def selectUTXOs(
      fromLockupScript: LockupScript.Asset,
      outputsTotalAmount: U256,
      outputsLength: Int,
      gasOpt: Option[GasBox],
      gasPrice: GasPrice
  ): IOResult[Either[String, UtxoUtils.Selected]] = {
    getUsableUtxos(fromLockupScript).map { utxos =>
      UtxoUtils.select(
        utxos,
        outputsTotalAmount,
        gasOpt,
        gasPrice,
        defaultGasPerInput,
        defaultGasPerOutput,
        dustUtxoAmount,
        outputsLength + 1,
        minimalGas
      )
    }
  }
}

object TxUtils {
  def isSpent(blockCaches: AVector[BlockCache], outputRef: TxOutputRef): Boolean = {
    blockCaches.exists(_.inputs.contains(outputRef))
  }
}
