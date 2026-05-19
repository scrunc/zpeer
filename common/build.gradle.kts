val nettyVersion = "4.1.115.Final"
val gsonVersion  = "2.10.1"

dependencies {
    compileOnly("io.netty:netty-all:$nettyVersion")
    compileOnly("com.google.code.gson:gson:$gsonVersion")
}
