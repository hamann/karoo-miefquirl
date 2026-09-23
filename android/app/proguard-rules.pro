# Only consulted when isMinifyEnabled is turned on in build.gradle.kts.
#
# karoo-ext passes its models across the process boundary with
# kotlinx.serialization, whose generated serializers are looked up reflectively
# and are therefore invisible to R8.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class io.hammerhead.karooext.models.** { *; }
-keepclassmembers class io.hammerhead.karooext.models.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
