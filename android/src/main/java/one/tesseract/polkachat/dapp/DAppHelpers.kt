package one.tesseract.polkachat.dapp

import android.util.Log
import io.emeraldpay.polkaj.scale.ScaleCodecReader
import jp.co.soramitsu.fearless_utils.extensions.fromHex
import jp.co.soramitsu.fearless_utils.extensions.toHexString
import jp.co.soramitsu.fearless_utils.hash.Hasher.xxHash128
import jp.co.soramitsu.fearless_utils.runtime.RuntimeSnapshot
import jp.co.soramitsu.fearless_utils.runtime.definitions.types.TypeReference
import jp.co.soramitsu.fearless_utils.runtime.definitions.types.composite.DictEnum
import jp.co.soramitsu.fearless_utils.runtime.definitions.types.composite.Struct
import jp.co.soramitsu.fearless_utils.runtime.definitions.types.composite.Vec
import jp.co.soramitsu.fearless_utils.scale.Schema
import jp.co.soramitsu.fearless_utils.scale.dataType.scalable
import jp.co.soramitsu.fearless_utils.scale.enum
import jp.co.soramitsu.fearless_utils.scale.schema
import jp.co.soramitsu.fearless_utils.scale.sizedByteArray
import jp.co.soramitsu.fearless_utils.scale.string
import jp.co.soramitsu.fearless_utils.scale.uint32
import jp.co.soramitsu.fearless_utils.scale.vector
import jp.co.soramitsu.fearless_utils.wsrpc.SocketService
import jp.co.soramitsu.fearless_utils.wsrpc.executeAsync
import jp.co.soramitsu.fearless_utils.wsrpc.logging.Logger
import jp.co.soramitsu.fearless_utils.wsrpc.request.runtime.RuntimeRequest
import jp.co.soramitsu.fearless_utils.wsrpc.response.RpcResponse
import jp.co.soramitsu.fearless_utils.wsrpc.subscription.response.SubscriptionChange
import kotlinx.coroutines.suspendCancellableCoroutine
import java.math.BigInteger
import java.util.LinkedList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object DAppTypes {
    const val METHOD_ADD = "4b050ea9"
    const val METHOD_GET = "2f865bd9"
    const val METHOD_LEN = "839b3548"

    object SResultErr: Schema<SResultErr>()

    object SResultOkLen: Schema<SResultOkLen>() {
        val length by uint32()
    }

    object SResultLen: Schema<SResultLen>() {
        val result by enum(scalable(SResultOkLen), scalable(SResultErr))
    }

    object SMessage: Schema<SMessage>() {
        val id by uint32()
        val sender by sizedByteArray(32)
        val text by string()
    }

    object SResultOkMessages: Schema<SResultOkMessages>() {
        val messages by vector(SMessage)
    }

    object SResultMessages: Schema<SResultMessages>() {
        val result by enum(scalable(SResultOkMessages), scalable(SResultErr))
    }

    object SMessageAddedEvent: Schema<SMessageAddedEvent>() {
        val message by schema(SMessage)
    }

    object SContractEvent: Schema<SContractEvent>() {
        val event by enum(scalable(SMessageAddedEvent))
    }
}

sealed interface TransactionStatus {
    val isFinalised: Boolean
    val isFinished: Boolean
    val isInBlock: Boolean
    val blockHash: ByteArray?

    object Future: TransactionStatus {
        override val isFinalised: Boolean = false
        override val isFinished: Boolean = false
        override val isInBlock: Boolean = false
        override val blockHash: ByteArray? = null
    }
    object Ready: TransactionStatus {
        override val isFinalised: Boolean = false
        override val isFinished: Boolean = false
        override val isInBlock: Boolean = false
        override val blockHash: ByteArray? = null
    }
    class Broadcast(val topics: List<String>): TransactionStatus {
        override val isFinalised: Boolean = false
        override val isFinished: Boolean = false
        override val isInBlock: Boolean = false
        override val blockHash: ByteArray? = null
    }
    class InBlock(hash: ByteArray): TransactionStatus {
        override val isFinalised: Boolean = false
        override val isFinished: Boolean = false
        override val isInBlock: Boolean = true
        override val blockHash: ByteArray = hash
    }
    class Retracted(hash: ByteArray): TransactionStatus {
        override val isFinalised: Boolean = false
        override val isFinished: Boolean = false
        override val isInBlock: Boolean = true
        override val blockHash: ByteArray = hash
    }
    class FinalityTimeout(hash: ByteArray): TransactionStatus {
        override val isFinalised: Boolean = false
        override val isFinished: Boolean = true
        override val isInBlock: Boolean = false
        override val blockHash: ByteArray = hash
    }
    class Finalized(hash: ByteArray): TransactionStatus {
        override val isFinalised: Boolean = true
        override val isFinished: Boolean = true
        override val isInBlock: Boolean = true
        override val blockHash: ByteArray = hash
    }
    class Usurped(hash: ByteArray): TransactionStatus {
        override val isFinalised: Boolean = false
        override val isFinished: Boolean = true
        override val isInBlock: Boolean = false
        override val blockHash: ByteArray = hash
    }
    object Dropped: TransactionStatus {
        override val isFinalised: Boolean = false
        override val isFinished: Boolean = true
        override val isInBlock: Boolean = false
        override val blockHash: ByteArray? = null
    }
    object Invalid: TransactionStatus {
        override val isFinalised: Boolean = false
        override val isFinished: Boolean = true
        override val isInBlock: Boolean = false
        override val blockHash: ByteArray? = null
    }

    companion object {
        fun fromJson(obj: Any): TransactionStatus {
            val name = obj as? String
            if (name != null) {
                return when (name) {
                    "future" -> Future
                    "ready" -> Ready
                    "dropped" -> Dropped
                    "invalid" -> Invalid
                    else -> throw Exception("Uknown TransactionState: $name")
                }
            }
            val map = obj as? Map<*, *>
            if (map == null || map.count() != 1) {
                throw Exception("Unknown TransactionState: $obj")
            }
            val parseHash = { any: Any? ->
                (any as? String)?.fromHex() ?: throw Exception("Bad hash: $any")
            }
            return when(map.keys.first() as? String) {
                "broadcast" -> {
                    val topics = (map.values.first()!! as List<*>).map { it as String }
                    Broadcast(topics)
                }
                "inBlock" -> InBlock(parseHash(map.values.first()))
                "retracted" -> Retracted(parseHash(map.values.first()))
                "finalityTimeout" -> FinalityTimeout(parseHash(map.values.first()))
                "finalized" -> Finalized(parseHash(map.values.first()))
                "usurped" -> Usurped(parseHash(map.values.first()))
                else -> throw Exception("Unknown TransactionState: $map")
            }
        }
    }
}

class ExtrinsicEvent(dictEnum: Any?) {
    val module: String
    val event: String
    val value: Map<String, Any?>

    val name: String
        get() = "$module.$event"

    override fun toString(): String = "$name: $value"

    init {
        val topEnum = dictEnum as DictEnum.Entry<*>
        module = topEnum.name
        val evEnum = topEnum.value as DictEnum.Entry<*>
        event = evEnum.name
        value = (evEnum.value as Struct.Instance).mapping
    }
}

class DAppLogger(
    private val tag: String, private val debug: Boolean
) : Logger {
    override fun log(message: String?) {
        if (debug) {
            Log.d(tag, message.toString())
        }
    }

    override fun log(throwable: Throwable?) {
        if (debug) {
            throwable?.printStackTrace()
        }
    }
}

fun RpcResponse.resOrThrow(): Any {
    if (error != null) { throw Exception("Request error: $error") }
    return result!!
}

suspend fun SocketService.sendAndWatchTx(
    tx: String, waitForFinalized: Boolean = true
) = suspendCancellableCoroutine { cont ->
    val cancellable = this.subscribe(
        RuntimeRequest("author_submitAndWatchExtrinsic", listOf(tx)),
        object: SocketService.ResponseListener<SubscriptionChange> {
            override fun onError(throwable: Throwable) {
                cont.resumeWithException(throwable)
            }

            override fun onNext(response: SubscriptionChange) {
                val status: TransactionStatus
                try {
                    status = TransactionStatus.fromJson(response.params.result)
                } catch (err: Throwable) {
                    cont.resumeWithException(err)
                    return
                }
                if (status.isFinalised) {
                    cont.resume(status.blockHash!!)
                } else if (status.isInBlock && !waitForFinalized) {
                    cont.resume(status.blockHash!!)
                } else if (status.isFinished) {
                    cont.resumeWithException(Exception("Transaction Failed: $status"))
                }
            }
        },
        "author_unwatchExtrinsic"
    )
    cont.invokeOnCancellation { cancellable.cancel() }
}

suspend fun SocketService.extrinsicIndex(tx: String, blockHash: ByteArray): Int? {
    val block = executeAsync(
        RuntimeRequest("chain_getBlock", listOf(blockHash.toHexString(true)))
    ).resOrThrow() as Map<*, *>
    val extrinsics = (block["block"] as Map<*, *>)["extrinsics"] as List<*>
    val index = extrinsics.indexOf(tx)
    return if (index >= 0) { index } else { null }
}

suspend fun SocketService.events(
    blockHash: ByteArray, runtime: RuntimeSnapshot
): Map<String, List<ExtrinsicEvent>> {
    val storageKey = "System".toByteArray().xxHash128() + "Events".toByteArray().xxHash128()
    val events = executeAsync(
        RuntimeRequest(
            "state_getStorage",
            listOf(storageKey.toHexString(true), blockHash.toHexString(true))
        )
    ).resOrThrow() as String
    val type = runtime.typeRegistry["EventRecord"]!!
    val records = Vec("EventRecords", TypeReference(type))
        .decode(ScaleCodecReader(events.fromHex()), runtime)
    val result = HashMap<String, MutableList<ExtrinsicEvent>>()
    for (record in records) {
        val evRecord = record as Struct.Instance
        val phase = evRecord.mapping["phase"]!! as DictEnum.Entry<*>
        val key = when (phase.name) {
            "ApplyExtrinsic" -> (phase.value as BigInteger).toString()
            else -> phase.name
        }
        val list = result[key] ?: LinkedList()
        list.add(ExtrinsicEvent(evRecord.mapping["event"]))
        result[key] = list
    }
    return result
}