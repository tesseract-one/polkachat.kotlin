package one.tesseract.polkachat

import jp.co.soramitsu.fearless_utils.runtime.AccountId
import one.tesseract.polkachat.dapp.DAppSigner
import one.tesseract.protocol.common.substrate.AccountType
import one.tesseract.protocol.common.substrate.GetAccountResponse

class Account(private val response: GetAccountResponse, val type: AccountType) : DAppSigner.Account {
    override val id: AccountId
        get() = response.publicKey

    val path: String
        get() = response.path
}