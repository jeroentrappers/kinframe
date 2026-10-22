package app.kinframe.core.auth

/** Credential issued at enrollment (README §3.2). The secret never leaves the device except to /devices/token. */
data class DeviceCredential(val deviceId: String, val secret: String)

interface CredentialStore {
    fun load(): DeviceCredential?
    fun save(credential: DeviceCredential)
    fun clear()
}

/** In-memory store for tests and previews. */
class MemoryCredentialStore(private var value: DeviceCredential? = null) : CredentialStore {
    override fun load() = value
    override fun save(credential: DeviceCredential) { value = credential }
    override fun clear() { value = null }
}
