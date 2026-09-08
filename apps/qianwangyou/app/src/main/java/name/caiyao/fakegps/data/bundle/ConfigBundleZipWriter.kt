package name.caiyao.fakegps.data.bundle

/** Zip assembly seam for the config bundle (delegates to the shared contract). */
object ConfigBundleZipWriter {
    fun write(files: Map<String, ByteArray>): ByteArray = ConfigBundleContract.writeZip(files)
}
