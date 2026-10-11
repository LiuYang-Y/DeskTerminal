# 高德 SDK 的混淆规则。
#
# 抄自官方《开发注意事项》的"代码混淆"一节，按本模块实际用到的三个包裁剪：
# 地图（3D V5.0.0 之后的那套规则）、定位、搜索。导航没引，所以不写 navi 那两行。
#
# 为什么一条都不能省：高德的类大量通过反射创建、以及由 so 反过来回调 Java 层，
# 名字被混淆掉之后现象是"编译通过、运行白屏/直接崩"，非常难查。
# 本文件通过 defaultConfig.consumerProguardFiles 下发给所有依赖 :map 的模块，
# 不用在每个 app 模块里重复一遍。

-keep class com.amap.api.maps.**{*;}
-keep class com.autonavi.**{*;}
-keep class com.amap.api.trace.**{*;}

-keep class com.amap.api.location.**{*;}
-keep class com.amap.api.fence.**{*;}
-keep class com.autonavi.aps.amapapi.model.**{*;}

-keep class com.amap.api.services.**{*;}

-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
