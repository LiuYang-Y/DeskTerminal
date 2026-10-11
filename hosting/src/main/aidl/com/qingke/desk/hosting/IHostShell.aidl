// 本应用进程  <->  Shizuku 用户服务进程 之间的唯一接口。
//
// 用户服务由 Shizuku 以 app_process 起在 **uid 2000（shell）** 下，
// 因此这里执行的命令天然带着 shell 的那些权限：
//   CONTROL_DISPLAY / INTERNAL_SYSTEM_WINDOW / INJECT_EVENTS
// —— 正是"把第三方 App 拉到虚拟屏上"和"往虚拟屏注入触摸"需要的两张牌，
// 普通应用进程一样都拿不到（Q1 探针实测：应用自己 setLaunchDisplayId 会被拒）。
package com.qingke.desk.hosting;

interface IHostShell {

    /**
     * 执行一条 shell 命令行。
     *
     * @return [exitCode, stdout, stderr]；exitCode 以字符串形式返回，AIDL 里
     *         三元组用数组传最省事，调用方自己 toInt()。
     */
    String[] exec(String command);

    // 下面两个是 Shizuku 用户服务的约定方法：服务端在解绑/退出时会回调，
    // 接口里不声明它拿不到，解绑就会失败。
    void destroy();

    void exit();
}
