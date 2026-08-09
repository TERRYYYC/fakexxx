# Contract v1 types cross a Binder boundary and are reconstructed reflectively by
# the kotlin-parcelize generated CREATOR. Keep them and their CREATOR fields so a
# minified consumer cannot strip or rename what the other app is about to read.
-keep class io.github.terryyyc.fakexxx.contract.v1.** { *; }
-keepclassmembers class io.github.terryyyc.fakexxx.contract.v1.** {
    public static final android.os.Parcelable$Creator CREATOR;
}
