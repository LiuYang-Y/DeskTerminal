# 高德 SDK 的混淆规则。
#
# 抄自官方《其他配置注意事项》"代码混淆"一节（该页 2025-05-12 更新），
# 按本模块实际要用到的包取用。高德的类大量通过反射创建、以及由 so 反过来回调
# Java 层，名字被混淆掉之后现象是"编译通过、运行白屏/直接崩"，非常难查。
# 本文件通过 defaultConfig.consumerProguardFiles 下发给所有依赖 :map 的模块，
# 不用在每个 app 模块里重复一遍。
#
# 2026-10-12 换导航合包后补上 #导航 V8.1.0 及以后 那三条：
# 合包里带 com.alibaba.idst.nui.*（阿里语音包，导航的语音播报靠它），
# 官方对 V8.1.0+ 的规则与该语音包是配套的，漏掉就是"编译过、一说话就崩"。

#3D地图 V5.0.0之后：
-keep class com.amap.api.maps.**{*;}
-keep class com.autonavi.**{*;}
-keep class com.amap.api.trace.**{*;}

#定位：
-keep class com.amap.api.location.**{*;}
-keep class com.amap.api.fence.**{*;}
-keep class com.autonavi.aps.amapapi.model.**{*;}

#搜索：
-keep class com.amap.api.services.**{*;}

#导航 V8.1.0及以后（本模块当前引入的 11.3.100 属于此档）：
-keep class com.amap.api.navi.**{*;}
-keep class com.alibaba.idst.nui.*{*;}
-keep class com.google.**{*;}

-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.alibaba.idst.nui.**
