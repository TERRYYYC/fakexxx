package name.caiyao.fakegps.mockprovider

interface MockProviderGateway {
    fun replaceGpsProvider()
    fun publish(config: MockLocationConfig)
    fun removeGpsProvider()
}
