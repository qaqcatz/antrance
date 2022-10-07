# antrance
android内部http服务器, 两种类型:

* AntranceIns: 通过插桩工具(abuilder, 后面会补上连接)植入到app中, 负责获取app的语句执行日志. 用户可主动访问AntranceIns提供的接口获取app的语句日志. app崩溃时AntranceIns会自动将日志上传到Antrance中.

* Antrance: 寄生在Accessibility Service中的Http Server. 主要功能如下:
  * 以xml的形式向用户提供当前界面的ui tree. 
  * 操作ui tree的节点, 目前只支持设置文本, 其余操作推荐用adb做.
  * 如同AntranceIns中介绍的, app崩溃时会将语句日志上传到Antrance中. 另外, Antrance封装了AntranceIns的全部功能(在用户调用这些接口时Antrance会将请求转发给AntranceIns, 并获取相应的结果), 用户可以无视AntranceIns的存在.

需要注意的是, 一个Android设备中只能有一个Antrance和一个AntranceIns. 而且这两个服务器的端口是固定的, 不能修改(Antrance:8624, AntranceIns:8625). 正确时返回200, 错误时返回400, 404, 500, 可以接收响应体看一下错误信息.

两个服务器的具体功能如下.

# AntranceIns

端口:8625

(1) GET /stmtlog

获取当前的语句日志. 格式如下:

```json
         json格式:
         { "projectId(当前程序的项目id, 用户指定)":"com.example.debugapp",
           "status(程序正常/崩溃)":true/false,
           "stmts(程序运行过程中执行的语句id)":[0, 3, 8001, 10234],
           "eventids(语句关联的eventids, 1<<0表示app启动)":["9","3","1","2"],
           "stackTrace(status为false时表示出现了uncaught exception, 需记录栈调用信息, status true时为空)": [
             "类@语句在文件中的源码行"
           ]
           "stackTraceOrigin": "原始栈信息"
         }
```

语句id等基本信息是通过abuilder生成的(后面会补上链接).

语句日志可以通过auiauto(https://github.com/qaqcatz/auiauto)解析成具体的覆盖率信息.

eventids是AntranceIns的一个特性, 马上就会介绍:

(2) GET /seteventid?id=xxx

> 虽然是GET接口, 实际是一个设置操作, 用GET只是为了方便.

我们可以在Android设备上做点击, 滑动等操作, 这些操作都可以视为事件.

在统计覆盖率时, 某些场景下可能需要了解每条语句被那些事件执行过.

因此对于每条语句, 我们为其提供了一个64位的flag:

* 第0位为1表示app启动时默认执行这条语句;
* 用户在执行一个事件前可以通过seteventid接口设置当前要执行的事件id, 假设id=1, 接下来对于所有执行过的语句, 其flag的第1位都会被设置成1;
* 可以看到每条语句最大可以标识64-1个事件, 为了保险我们将这个上限设为了62. 超出62的事件按62计算.一般情况下够用了. 

(3) 手动上传崩溃

我们通过`Thread.UncaughtExceptionHandler`自动捕获app崩溃. 然而有些开发者可能也会用这个接口做崩溃处理, 可能影响AtranceIns的崩溃捕获. 这种情况下用户需要手动上传崩溃信息到Antrance. 具体流程如下:

1. 获取当前的崩溃栈, 作为`String stackTraceOrigin`;

2. 将stackTraceOrigin拆分成一条条"类@语句在文件中的源码行"的形式, 存储在`List<String> crashStack`中

3. 通过反射调用AntranceIns的`myCrashJson`接口, 生成语句日志:

   ```java
   public static String myCrashJson(List<String> crashStack, String stackTraceOrigin)
   ```

4. 将语句日志上传到Antrance, 端口为8624, 调用POST /stmtlog, 请求体为JSON格式, 内容就是`myCrashJson`的返回值.

另一种做法是关闭应用的崩溃处理, 这样AntranceIns就可以正常捕获崩溃了.

# Antrance

端口:8624

(1) GET /uitree

xml格式:

```java
public static String dumpUITree(List<AccessibilityWindowInfo> windows) {
        Document document = DocumentHelper.createDocument();
        Element rootElement = document.addElement("rt");

        // For convenience the returned windows are ordered in a descending layer order,
        // which is the windows that are on top are reported first.
        for (int i = 0; i < windows.size(); i++) {
            AccessibilityWindowInfo windowInfo = windows.get(i);
            AccessibilityNodeInfo rootNode = windowInfo.getRoot();
            if (rootNode == null) continue;
            dumpUITreeDFS(rootElement, rootNode, 0, i);
        }

        return document.asXML();
    }

    private static void dumpUITreeDFS(Element fatherElement, AccessibilityNodeInfo curNode,
                                      int depth, int index) {
        if (!curNode.isVisibleToUser()) {
            return;
        }
        Rect bds = new Rect();
        curNode.getBoundsInScreen(bds);
        if (bds.right - bds.left <= 5 || bds.bottom - bds.top <= 5) {
            return;
        }
//        curNode.refresh();
        String pkg = safeCharSeqToString(curNode.getPackageName());
        String cls = safeCharSeqToString(curNode.getClassName());
        String res = safeCharSeqToString(curNode.getViewIdResourceName());
        String dsc = safeCharSeqToString(curNode.getContentDescription());
        String txt = safeCharSeqToString(curNode.getText());
        /*
         * 计算操作码.
         * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
         * 支持|运算, 从低位到高位:
         * 1位: clickable
         * 2位: longClickable
         * 3位: editable
         * 4位: scrollable
         * 5位: checkable
         * */
        int op = 0;
        if (curNode.isClickable()) {
            op |= 1;
        }
        if (curNode.isLongClickable()) {
            op |= 2;
        }
        if (curNode.isEditable()) {
            op |= 4;
        }
        if (curNode.isScrollable()) {
            op |= 8;
        }
        if (curNode.isCheckable()) {
            op |= 16;
        }
        int sta = 0;
        if (curNode.isChecked()) {
            sta |= 1;
        }

        Element curElement = fatherElement.addElement("nd")
                .addAttribute("dp", ""+depth)
                .addAttribute("idx", ""+index)
                .addAttribute("bds", bds.left+"@"+bds.top+"@"+bds.right+"@"+bds.bottom)
                .addAttribute("pkg", ""+pkg)
                .addAttribute("cls", ""+cls)
                .addAttribute("res", ""+res)
                .addAttribute("dsc", ""+dsc)
                .addAttribute("txt", ""+txt)
                .addAttribute("op", ""+op)
                .addAttribute("sta", ""+sta);

        int count = curNode.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo childNode = curNode.getChild(i);
            if (childNode == null) continue;
            dumpUITreeDFS(curElement, childNode, depth+1, i);
        }

        curNode.recycle();
    }
```

(2) POST /perform

请求体: json格式, 实际上是auiauto的事件格式(https://github.com/qaqcatz/auiauto):

```golang
type Event struct {
	// 类型, 目前只支持keyevent
	MType string `json:"type"`
	// 设置的文本
	MValue string `json:"value"`
	// 格式: 包@类@res id@操作码@深度
	// 操作码:
	// 支持|运算, 从低位到高位:
	// 0位: clickable
	// 1位: longClickable
	// 2位: editable
	// 3位: scrollable
	// 4位: checkable
	MObject string `json:"object"`
	// object的index前缀, 根节点->当前节点
	// ui元素匹配规则:
	// object匹配, 如果有多个, 找最相似的那个
	// 相似度定义:
	// prefix长度不一致相似度为-1(这个其实已经涵盖在了object比较中, 因为object中包含深度信息)
	// 否则定义为对应位index相等的个数, 比如两个前缀1 2 3 4, 1 0 3 3, 0号位(1)和2号位(3)相等, 相似度为2
	MPrefix []int `json:"prefix"`
}
```

(3) GET /seteventid

同AntranceIns

(4) 日志获取

本质是对AntranceIns功能的封装, 但有几个规则要注意一下:

* 你可以使用GET /stmtlog与AntranceIns通信, 获取最新日志.
* AntranceIns在app崩溃时会自动上传日志到Antrance, 此时调用GET /stmtlog不会与AntranceIns进行通信, 而是取走之前上传的错误日志. 注意这里是取走, 再次调用GET /stmtlog就能和AntranceIns正常通信了.
* 如果AntranceIns上传了多个崩溃日志, 我们只会保留最新的那一份.
* 你可以使用GET /iscrash判断当前app是否发生了崩溃, 这是个轻量级的接口.
* 你可以使用GET /init清除Antrance中的崩溃日志. 务必在每次测试开始时执行一下.
* POST /stmtlog负责接收AntranceIns的崩溃日志, 这个在AntranceIns中已经介绍过.

> 历史遗留问题: 
>
> 早期版本的AntranceIns规定了app被kill前只能获取一次日志, 调用/stmtlog获取一次以上的日志时会出错, 状态码500.
>
> 在qaqcatz仓库中上传的所有版本都没这个问题, 不过如果你如果用了其他仓库的版本就需要额外注意这一点.

# 更新auiauto kernel

antrance实质是auiauto(https://github.com/qaqcatz/auiauto) kernel的组成部分. 我们提供了一个脚本, 可以方便地将编译后的Antrance和AntranceIns拷贝到auiauto的kernel中, 需要指定auiauto的database路径为第一个参数, 具体如下:

```shell
./updateauiauto.sh auiauto的database路径
```





