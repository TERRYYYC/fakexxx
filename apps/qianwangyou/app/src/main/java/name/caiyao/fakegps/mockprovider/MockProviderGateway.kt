package name.caiyao.fakegps.mockprovider

/** 读回观测到的 mock 位置（#176 A 层探针的事实源，非自记录状态）。 */
data class MockReadback(
    val latitude: Double,
    val longitude: Double,
)

interface MockProviderGateway {
    fun replaceGpsProvider()
    fun publish(config: MockLocationConfig)
    fun removeGpsProvider()

    /**
     * 读回 test provider **当前实际在发**的位置（LocationManager 事实源）。
     *
     * 默认 null = 该实现无法读回——[name.caiyao.fakegps.integration.v1.DeliveryReadbackGate]
     * 把不可读视为不可信（fail-closed），绝不当作"发布成功"。不抛异常：appops 被
     * 拒、provider 未注册、权限丢失等读回失败统一归为 null，由门给出 mismatch 诊断。
     */
    fun readbackLastLocation(): MockReadback? = null
}
