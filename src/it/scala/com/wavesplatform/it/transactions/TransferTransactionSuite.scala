package com.wavesplatform.it.transactions

import com.wavesplatform.it.TransferSending
import com.wavesplatform.it.util._
import org.scalatest.CancelAfterFailure
import scorex.account.{AddressOrAlias, PrivateKeyAccount}
import scorex.api.http.Mistiming
import scorex.transaction.assets.TransferTransaction

import scala.concurrent.Await
import scala.concurrent.Future.{sequence, traverse}
import scala.concurrent.duration._

class TransferTransactionSuite extends BaseTransactionSuite with TransferSending with CancelAfterFailure {

  private val waitCompletion = 2.minutes
  private val defaultQuantity = 100000

  test("asset transfer changes sender's and recipient's asset balance; issuer's.waves balance is decreased by fee") {
    val f = for {
      _ <- assertBalances(firstAddress, 100.waves, 100.waves)
      _ <- assertBalances(secondAddress, 100.waves, 100.waves)

      issuedAssetId <- sender.issue(firstAddress, "name", "description", defaultQuantity, 2, reissuable = false, fee = 10.waves).map(_.id)
      _ <- waitForHeightAraiseAndTxPresent(issuedAssetId, 1)
      _ <- assertBalances(firstAddress, 90.waves, 90.waves)
        .zip(assertAssetBalance(firstAddress, issuedAssetId, defaultQuantity))

      transferTransactionId <- sender.transfer(firstAddress, secondAddress, defaultQuantity, fee = 10.waves, Some(issuedAssetId)).map(_.id)
      _ <- waitForHeightAraiseAndTxPresent(transferTransactionId, 1)
      _ <- assertBalances(firstAddress, 80.waves, 80.waves)
        .zip(assertBalances(secondAddress, 100.waves, 100.waves))
        .zip(assertAssetBalance(firstAddress, issuedAssetId, 0))
        .zip(assertAssetBalance(secondAddress, issuedAssetId, defaultQuantity))
    } yield succeed

    Await.result(f, waitCompletion)
  }

  test("waves transfer changes waves balances and eff.b.") {
    val f = for {
      _ <- assertBalances(firstAddress, 80.waves, 80.waves)
        .zip(assertBalances(secondAddress, 100.waves, 100.waves))

      transferId <- sender.transfer(firstAddress, secondAddress, 5.waves, fee = 5.waves).map(_.id)
      _ <- waitForHeightAraiseAndTxPresent(transferId, 1)
      _ <- assertBalances(firstAddress, 70.waves, 70.waves)
        .zip(assertBalances(secondAddress, 105.waves, 105.waves))
    } yield succeed

    Await.result(f, waitCompletion)
  }

  test("invalid signed waves transfer should not be in UTX or blockchain") {
    def invalidByTsTx(ts: Long) = TransferTransaction.create(None,
      PrivateKeyAccount.fromSeed(sender.accountSeed).right.get,
      AddressOrAlias.fromString(sender.address).right.get,
      1,
      ts,
      None,
      1.waves,
      Array.emptyByteArray
    ).right.get

    val invalidTimestamps: Seq[Long] = Seq(
      System.currentTimeMillis() + 1.day.toMillis,
      1e15.toLong // NODE-416
    )

    for (timestamp <- invalidTimestamps) {
      val tx = invalidByTsTx(timestamp)
      val id = tx.id()
      val req = createSignedTransferRequest(tx)
      val f = for {
        _ <- expectErrorResponse(sender.signedTransfer(req)) { x =>
          x.error == Mistiming.Id
        }
        _ <- sequence(nodes.map(_.ensureTxDoesntExist(id.base58)))
      } yield succeed

      Await.result(f, waitCompletion)
    }
  }

  test("can not make transfer without having enough of fee") {
    val f = for {
      fb <- traverse(nodes)(_.height).map(_.min)

      _ <- assertBalances(firstAddress, 70.waves, 70.waves)
        .zip(assertBalances(secondAddress, 105.waves, 105.waves))

      transferFailureAssertion <- assertBadRequest(sender.transfer(secondAddress, firstAddress, 104.waves, fee = 2.waves))

      _ <- traverse(nodes)(_.waitForHeight(fb + 2))

      _ <- assertBalances(firstAddress, 70.waves, 70.waves)
        .zip(assertBalances(secondAddress, 105.waves, 105.waves))
    } yield transferFailureAssertion

    Await.result(f, waitCompletion)
  }


  test("can not make transfer without having enough of waves") {
    val f = for {
      fb <- traverse(nodes)(_.height).map(_.min)

      _ <- assertBalances(firstAddress, 70.waves, 70.waves)
        .zip(assertBalances(secondAddress, 105.waves, 105.waves))

      transferFailureAssertion <- assertBadRequest(sender.transfer(secondAddress, firstAddress, 106.waves, fee = 1.waves))

      _ <- traverse(nodes)(_.waitForHeight(fb + 2))

      _ <- assertBalances(firstAddress, 70.waves, 70.waves)
        .zip(assertBalances(secondAddress, 105.waves, 105.waves))
    } yield transferFailureAssertion

    Await.result(f, waitCompletion)
  }

  test("can not make transfer without having enough of effective balance") {
    val f = for {
      fb <- traverse(nodes)(_.height).map(_.min)

      _ <- assertBalances(firstAddress, 70.waves, 70.waves)
        .zip(assertBalances(secondAddress, 105.waves, 105.waves))

      createdLeaseTxId <- sender.lease(firstAddress, secondAddress, 5.waves, fee = 5.waves).map(_.id)
      _ <- waitForHeightAraiseAndTxPresent(createdLeaseTxId, 1)

      _ <- assertBalances(firstAddress, 65.waves, 60.waves)
        .zip(assertBalances(secondAddress, 105.waves, 110.waves))

      transferFailureAssertion <- assertBadRequest(sender.transfer(firstAddress, secondAddress, 64.waves, fee = 1.waves))

      _ <- traverse(nodes)(_.waitForHeight(fb + 2))

      _ <- assertBalances(firstAddress, 65.waves, 60.waves)
        .zip(assertBalances(secondAddress, 105.waves, 110.waves))
    } yield transferFailureAssertion

    Await.result(f, waitCompletion)
  }

  test("can not make transfer without having enough of your own waves") {
    val f = for {
      fb <- traverse(nodes)(_.height).map(_.min)

      _ <- assertBalances(firstAddress, 65.waves, 60.waves)
        .zip(assertBalances(secondAddress, 105.waves, 110.waves))

      createdLeaseTxId <- sender.lease(firstAddress, secondAddress, 5.waves, fee = 5.waves).map(_.id)

      _ <- waitForHeightAraiseAndTxPresent(createdLeaseTxId, 1)

      _ <- assertBalances(firstAddress, 60.waves, 50.waves)
        .zip(assertBalances(secondAddress, 105.waves, 115.waves))

      transferFailureAssertion <- assertBadRequest(sender.transfer(secondAddress, firstAddress, 109.waves, fee = 1.waves))

      _ <- traverse(nodes)(_.waitForHeight(fb + 2))

      _ <- assertBalances(firstAddress, 60.waves, 50.waves)
        .zip(assertBalances(secondAddress, 105.waves, 115.waves))
    } yield transferFailureAssertion

    Await.result(f, waitCompletion)
  }

  test("can forge block with sending majority of some asse to self and to other account") {
    val f = for {
      _ <- assertBalances(firstAddress, 60.waves, 50.waves)
        .zip(assertBalances(secondAddress, 105.waves, 115.waves))

      assetId <- sender.issue(firstAddress, "second asset", "description", defaultQuantity, 0, reissuable = false, fee = 1.waves).map(_.id)

      _ <- waitForHeightAraiseAndTxPresent(assetId, 1)

      _ <- assertBalances(firstAddress, 59.waves, 49.waves)
        .zip(assertAssetBalance(firstAddress, assetId, defaultQuantity))

      tx1 <- sender.transfer(firstAddress, firstAddress, defaultQuantity, fee = 1.waves, Some(assetId)).map(_.id)
      tx2 <- sender.transfer(firstAddress, secondAddress, defaultQuantity / 2, fee = 1.waves, Some(assetId)).map(_.id)

      height <- traverse(nodes)(_.height).map(_.max)
      _ <- traverse(nodes)(_.waitForHeight(height + 1))
      _ <- traverse(nodes)(_.waitForTransaction(tx1))
        .zip(traverse(nodes)(_.waitForTransaction(tx2)))

      _ <- traverse(nodes)(_.waitForHeight(height + 5))

      _ <- assertBalances(firstAddress, 57.waves, 47.waves)
        .zip(assertBalances(secondAddress, 105.waves, 115.waves))
    } yield succeed

    Await.result(f, waitCompletion)
  }
}
