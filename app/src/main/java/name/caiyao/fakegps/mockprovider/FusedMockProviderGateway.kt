package name.caiyao.fakegps.mockprovider

interface FusedMockProviderGateway {
    fun enable()
    fun publish(config: MockLocationConfig)
    fun disable()
}
