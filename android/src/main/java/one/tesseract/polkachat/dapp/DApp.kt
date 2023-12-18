package one.tesseract.polkachat.dapp

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.neovisionaries.ws.client.WebSocketFactory
import jp.co.soramitsu.fearless_utils.extensions.fromHex
import jp.co.soramitsu.fearless_utils.runtime.AccountId
import jp.co.soramitsu.fearless_utils.runtime.RuntimeSnapshot
import jp.co.soramitsu.fearless_utils.runtime.definitions.TypeDefinitionParser
import jp.co.soramitsu.fearless_utils.runtime.definitions.TypeDefinitionsTree
import jp.co.soramitsu.fearless_utils.runtime.definitions.dynamic.DynamicTypeResolver
import jp.co.soramitsu.fearless_utils.runtime.definitions.dynamic.extentsions.GenericsExtension
import jp.co.soramitsu.fearless_utils.runtime.definitions.registry.TypeRegistry
import jp.co.soramitsu.fearless_utils.runtime.definitions.registry.v14Preset
import jp.co.soramitsu.fearless_utils.runtime.definitions.types.composite.DictEnum
import jp.co.soramitsu.fearless_utils.runtime.definitions.types.composite.Struct
import jp.co.soramitsu.fearless_utils.runtime.definitions.types.fromHex
import jp.co.soramitsu.fearless_utils.runtime.definitions.types.generics.MULTI_ADDRESS_ID
import jp.co.soramitsu.fearless_utils.runtime.definitions.types.toHex
import jp.co.soramitsu.fearless_utils.runtime.definitions.v14.TypesParserV14
import jp.co.soramitsu.fearless_utils.runtime.extrinsic.ExtrinsicBuilder
import jp.co.soramitsu.fearless_utils.runtime.metadata.GetMetadataRequest
import jp.co.soramitsu.fearless_utils.runtime.metadata.RuntimeMetadataReader
import jp.co.soramitsu.fearless_utils.runtime.metadata.builder.VersionedRuntimeBuilder
import jp.co.soramitsu.fearless_utils.runtime.metadata.v14.RuntimeMetadataSchemaV14
import jp.co.soramitsu.fearless_utils.scale.EncodableStruct
import jp.co.soramitsu.fearless_utils.scale.Field
import jp.co.soramitsu.fearless_utils.scale.Schema
import jp.co.soramitsu.fearless_utils.scale.dataType.string
import jp.co.soramitsu.fearless_utils.scale.dataType.toByteArray
import jp.co.soramitsu.fearless_utils.scale.dataType.uint32
import jp.co.soramitsu.fearless_utils.ss58.SS58Encoder
import jp.co.soramitsu.fearless_utils.wsrpc.SocketService
import jp.co.soramitsu.fearless_utils.wsrpc.executeAsync
import jp.co.soramitsu.fearless_utils.wsrpc.interceptor.WebSocketResponseInterceptor
import jp.co.soramitsu.fearless_utils.wsrpc.recovery.Reconnector
import jp.co.soramitsu.fearless_utils.wsrpc.request.RequestExecutor
import jp.co.soramitsu.fearless_utils.wsrpc.request.runtime.RuntimeRequest
import jp.co.soramitsu.fearless_utils.wsrpc.request.runtime.chain.RuntimeVersion
import jp.co.soramitsu.fearless_utils.wsrpc.request.runtime.chain.RuntimeVersionRequest
import jp.co.soramitsu.fearless_utils.wsrpc.response.RpcResponse
import java.io.Reader
import java.math.BigInteger

class DApp(
    private val contract: AccountId,
    private val service: SocketService,
    private val runtime: RuntimeSnapshot,
    private val metadata: EncodableStruct<*>,
    private val version: RuntimeVersion,
    private val ss58Format: Short,
    private val genesisHash: ByteArray
) {
    companion object {
        suspend fun new(baseTypes: Reader, netTypes: Reader?, url: String, contract: String): DApp {
            val gson = Gson()
            val contractId = SS58Encoder.decode(contract)

            val service = connect(gson, url)
            val metadata = service.executeAsync(GetMetadataRequest).resOrThrow() as String
            val parsedMetadata = RuntimeMetadataReader.read(metadata)
            val runtime = createRuntime(
                JsonReader(baseTypes),
                netTypes?.let { JsonReader(it) },
                parsedMetadata, gson
            )

            val versionMap = service.executeAsync(RuntimeVersionRequest()).resOrThrow() as Map<*, *>
            val version = RuntimeVersion(
                (versionMap["specVersion"]!! as Double).toInt(),
                (versionMap["transactionVersion"]!! as Double).toInt()
            )

            val hashStr = service.executeAsync(
                RuntimeRequest("chain_getBlockHash", listOf(0))
            ).resOrThrow() as String
            val hash = hashStr.fromHex()

            val ss58Res = service.executeAsync(
                RuntimeRequest("system_properties", listOf())
            ).resOrThrow() as Map<*, *>
            val ss58 = ((ss58Res["ss58Format"] as? Double) ?: 42.0).toInt().toShort()

            return DApp(contractId, service, runtime, parsedMetadata.metadata, version, ss58, hash)
        }

        private fun connect(gson: Gson = Gson(), url: String): SocketService {
            val webSocketFactory = WebSocketFactory()
            val reconnector = Reconnector()
            val logger = DAppLogger("DApp", true)
            val executor = RequestExecutor()

            val service = SocketService(gson, logger, webSocketFactory, reconnector, executor)
            service.setInterceptor(object : WebSocketResponseInterceptor {
                override fun onRpcResponseReceived(rpcResponse: RpcResponse): WebSocketResponseInterceptor.ResponseDelivery {
                    return WebSocketResponseInterceptor.ResponseDelivery.DELIVER_TO_SENDER
                }
            })
            service.start(url)

            return service
        }

        private fun createRuntime(
            baseTypes: JsonReader, netTypes: JsonReader?,
            metadata: RuntimeMetadataReader, gson: Gson
        ): RuntimeSnapshot {
            val metadataTypePreset = TypesParserV14.parse(
                metadata.metadata[RuntimeMetadataSchemaV14.lookup], v14Preset()
            )

            val baseTypesTree = gson.fromJson<TypeDefinitionsTree>(baseTypes, TypeDefinitionsTree::class.java)
            val baseTypePreset = TypeDefinitionParser.parseBaseDefinitions(baseTypesTree, metadataTypePreset)

            val completeTypes = netTypes?.let {
                val netTypesTree = gson.fromJson<TypeDefinitionsTree>(it, TypeDefinitionsTree::class.java)
                TypeDefinitionParser.parseNetworkVersioning(netTypesTree, baseTypePreset)
            } ?: baseTypePreset

            val typeRegistry = TypeRegistry(
                types = completeTypes,
                dynamicTypeResolver = DynamicTypeResolver(
                    DynamicTypeResolver.DEFAULT_COMPOUND_EXTENSIONS + GenericsExtension
                )
            )

            return RuntimeSnapshot(
                typeRegistry,
                VersionedRuntimeBuilder.buildMetadata(metadata, typeRegistry)
            )
        }
    }

    suspend fun messageCount(): UInt {
        return call(
            DAppTypes.METHOD_LEN.fromHex(), DAppTypes.SResultLen,
            DAppTypes.SResultLen.result, DAppTypes.SResultOkLen.length
        )
    }

    class Message(
        val id: UInt, val sender: String, val text: String
    ) {
        override fun toString(): String {
            return "[$id]($sender): $text"
        }
    }

    fun <A: DAppSigner.Account> addressFromAccount(account: A): String =
        SS58Encoder.encode(account.id, ss58Format)

    suspend fun messages(from: UInt, to: UInt): List<Message> {
        val params = DAppTypes.METHOD_GET.fromHex() +
                uint32.toByteArray(from) +
                uint32.toByteArray(to)
        return call(
            params, DAppTypes.SResultMessages,
            DAppTypes.SResultMessages.result,
            DAppTypes.SResultOkMessages.messages
        ).map { Message(
            it[DAppTypes.SMessage.id],
            SS58Encoder.encode(it[DAppTypes.SMessage.sender], ss58Format),
            it[DAppTypes.SMessage.text]
        ) }
    }

    suspend fun <A: DAppSigner.Account> send(from: A, message: String, signer: DAppSigner<A>): Message {
        val accountStr = SS58Encoder.encode(from.id, ss58Format)
        val nonce = (service.executeAsync(
            RuntimeRequest("system_accountNextIndex", listOf(accountStr))
        ).resOrThrow() as Double).toBigDecimal().toBigInteger()

        val tx = ExtrinsicBuilder(
            runtime, nonce, version, genesisHash, from.id,
            DAppSignerAdapter(from, signer, metadata)
        ).call("Contracts", "call", mapOf(
            "dest" to DictEnum.Entry(MULTI_ADDRESS_ID, contract),
            "value" to BigInteger("0"),
            "gas_limit" to Struct.Instance(mapOf(
                "refTime" to BigInteger("9375000000"),
                "proofSize" to BigInteger("524288")
            )),
            "storage_deposit_limit" to null,
            "data" to DAppTypes.METHOD_ADD.fromHex() + string.toByteArray(message)
        )).build(false)

        val blockHash = service.sendAndWatchTx(tx, waitForFinalized = false)
        val index = service.extrinsicIndex(tx, blockHash) ?: throw Exception("Extrinsic not found in: $blockHash")
        val events = service.events(blockHash, runtime)[index.toString()]!!
        if (events.find { it.name == "System.ExtrinsicSuccess" } == null) {
            throw Exception("Transaction failed")
        }
        val contractTopEvent = events.find { it.name == "Contracts.ContractEmitted" }
        val contractEvent = DAppTypes.SContractEvent.read(contractTopEvent!!.value["data"] as ByteArray)
        val addedEvent = (contractEvent[DAppTypes.SContractEvent.event] as EncodableStruct<*>)
        val addedMessage = addedEvent[DAppTypes.SMessageAddedEvent.message] as EncodableStruct<*>
        return Message(
            addedMessage[DAppTypes.SMessage.id],
            SS58Encoder.encode(addedMessage[DAppTypes.SMessage.sender], ss58Format),
            addedMessage[DAppTypes.SMessage.text]
        )
    }

    private suspend fun <R, RT: Schema<RT>>call(
        params: ByteArray, result: RT, resField: Field<Any?>, resOk: Field<R>
    ): R {
        val call = Struct.Instance(mapOf(
            "origin" to contract,
            "dest" to contract,
            "value" to BigInteger("0"),
            "gasLimit" to null,
            "storageDepositLimit" to null,
            "inputData" to params
        ))
        val callType = runtime.typeRegistry["ContractCallRequest"]!! as Struct
        val encodedCall = callType.toHex(runtime, call)

        val req = RuntimeRequest("state_call", listOf("ContractsApi_call", encodedCall))
        val response = service.executeAsync(req).resOrThrow() as String
        val execResult = runtime.typeRegistry["ContractExecResult"]!!.fromHex(
            runtime, response
        ) as Struct.Instance

        val resDict = (execResult.mapping["result"]!! as DictEnum.Entry<*>)
        if (resDict.name != "Ok") {
            throw Exception("Call error: " + resDict.value.toString())
        }

        val msRes = result.read((resDict.value as Struct.Instance).mapping["data"]!! as ByteArray)
        val struct = msRes[resField] as EncodableStruct<*>
        if (struct.schema == DAppTypes.SResultErr) {
            throw Exception("SC Error!!!")
        }
        return struct[resOk]
    }
}
