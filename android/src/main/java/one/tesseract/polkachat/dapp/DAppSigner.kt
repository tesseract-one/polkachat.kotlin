package one.tesseract.polkachat.dapp

import jp.co.soramitsu.fearless_utils.encrypt.EncryptionType
import jp.co.soramitsu.fearless_utils.encrypt.MultiChainEncryption
import jp.co.soramitsu.fearless_utils.encrypt.SignatureWrapper
import jp.co.soramitsu.fearless_utils.encrypt.keypair.substrate.Sr25519Keypair
import jp.co.soramitsu.fearless_utils.hash.Hasher.blake2b256
import jp.co.soramitsu.fearless_utils.runtime.AccountId
import jp.co.soramitsu.fearless_utils.encrypt.Signer as MessageSigner
import jp.co.soramitsu.fearless_utils.runtime.extrinsic.signer.Signer
import jp.co.soramitsu.fearless_utils.runtime.extrinsic.signer.SignerPayloadExtrinsic
import jp.co.soramitsu.fearless_utils.runtime.extrinsic.signer.SignerPayloadRaw
import jp.co.soramitsu.fearless_utils.runtime.extrinsic.signer.encodedSignaturePayload
import jp.co.soramitsu.fearless_utils.runtime.metadata.v14.ExtrinsicMetadataV14
import jp.co.soramitsu.fearless_utils.runtime.metadata.v14.LookupSchema
import jp.co.soramitsu.fearless_utils.runtime.metadata.v14.PortableType
import jp.co.soramitsu.fearless_utils.runtime.metadata.v14.RegistryType
import jp.co.soramitsu.fearless_utils.runtime.metadata.v14.RuntimeMetadataSchemaV14
import jp.co.soramitsu.fearless_utils.scale.invoke
import jp.co.soramitsu.fearless_utils.scale.EncodableStruct
import jp.co.soramitsu.fearless_utils.scale.toByteArray

interface DAppSigner<A: DAppSigner.Account> {
    interface Account { val id: AccountId }

    suspend fun sign(
        account: A, payload: ByteArray, types: ByteArray, metadata: ByteArray
    ): SignatureWrapper
}

class DAppSignerAdapter<A: DAppSigner.Account>(
    private val from: A,
    private val signer: DAppSigner<A>,
    private val metadata: EncodableStruct<*>
): Signer {
    override suspend fun signRaw(payload: SignerPayloadRaw): SignatureWrapper {
        throw Exception("signRaw should not be called!")
    }

    override suspend fun signExtrinsic(payloadExtrinsic: SignerPayloadExtrinsic): SignatureWrapper {
        if (!from.id.contentEquals(payloadExtrinsic.accountId)) {
            throw Exception(
                "Different account id: ${payloadExtrinsic.accountId}, expected: ${from.id}"
            )
        }
        val types = metadata[RuntimeMetadataSchemaV14.lookup]
        val callType = types[LookupSchema.types].find {
            it[PortableType.type][RegistryType.path].lastOrNull() == "RuntimeCall"
        }

        val oExtMeta = metadata[RuntimeMetadataSchemaV14.extrinsic]

        val extrinsicMeta = ExtrinsicMetadataV14 { meta ->
            meta[ExtrinsicMetadataV14.version] = oExtMeta[ExtrinsicMetadataV14.version]
            meta[ExtrinsicMetadataV14.signedExtensions] = oExtMeta[ExtrinsicMetadataV14.signedExtensions]
            meta[ExtrinsicMetadataV14.type] = callType!![PortableType.id]
        }

        return signer.sign(
            from, payloadExtrinsic.encodedSignaturePayload(false),
            types.toByteArray(), extrinsicMeta.toByteArray()
        )
    }
}
