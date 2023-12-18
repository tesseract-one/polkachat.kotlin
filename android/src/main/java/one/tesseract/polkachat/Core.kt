package one.tesseract.polkachat

import java.io.InputStreamReader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.app.Application

import one.tesseract.client.Tesseract

import one.tesseract.polkachat.dapp.DApp
import one.tesseract.protocol.common.substrate.AccountType
import one.tesseract.protocol.kotlin.SubstrateService

import one.tesseract.polkachat.dapp.send

class Core(private val application: Application) {
    private val tesseract: Tesseract = Tesseract.default(application)
    private val service: SubstrateService = tesseract.service(SubstrateService::class)
    private var _dApp: DApp? = null
    private var _account: Account? = null
    private val dAppMutex = Mutex()
    private val accountMutex = Mutex()

    private suspend fun dApp(): DApp = dAppMutex.withLock {
        val dApp = _dApp ?: DApp.new(
            InputStreamReader(application.assets.open("base.json")),
            InputStreamReader(application.assets.open("rococo.json")),
            "wss://rococo-contracts-rpc.polkadot.io:443",
            "5GZRb5XZVCTsH6VSxT3e8tE3qQmaiq4hJhxgdoFg8iijP3S9"
        )
        if(_dApp == null) {
            _dApp = dApp
        }
        dApp
    }

    private suspend fun accountInternal(): Account = accountMutex.withLock {
        val accountType = AccountType.Sr25519

        val account = _account ?: Account(service.getAccount(accountType), accountType)
        if (_account == null) {
            _account = account
        }
        account
    }

    suspend fun account(): String {
        return dApp().addressFromAccount(accountInternal())
    }

    suspend fun messages(from: UInt): List<String> {
        val count = dApp().messageCount()
        val messages = dApp().messages(from, count)
        return messages.map { it.text }
    }

    suspend fun send(message: String): Unit {
        val account = accountInternal()
        dApp().send(account, message, service)
    }
}