package one.tesseract.polkachat.dapp

import jp.co.soramitsu.fearless_utils.encrypt.SignatureWrapper
import one.tesseract.polkachat.Account
import one.tesseract.protocol.common.substrate.AccountType
import one.tesseract.protocol.kotlin.SubstrateService

class TesseractSigner(private val service: SubstrateService): DAppSigner<Account> {
    override suspend fun sign(
        account: Account,
        payload: ByteArray,
        types: ByteArray,
        metadata: ByteArray
    ): SignatureWrapper {
        val signature = service.signTransaction(account.type, account.path, payload, metadata, types)

        return when (account.type) {
            AccountType.Sr25519 -> SignatureWrapper.Sr25519(signature)
            AccountType.Ed25519 -> TODO("We don't use this account type in the dApp")
            AccountType.Ecdsa -> TODO("We don't use this account type in the dAPp")
        }
    }
}

suspend fun DApp.send(from: Account, message: String, service: SubstrateService): DApp.Message {
    val signer = TesseractSigner(service)
    return this.send(from, message, signer)
}