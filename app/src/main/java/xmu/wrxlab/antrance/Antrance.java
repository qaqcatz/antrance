package xmu.wrxlab.antrance;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 这里需要着重说明日志的获取方式:
 * 1. 你可以使用/stmtlog与antrance-ins通信, 获取最新日志.
 * 2. antrance-ins在app崩溃时会自动上传日志到antrance, 此时调用/stmtlog不会与antrance-ins进行通信,
 * 而是取走之前上传的错误日志. 注意这里是取走, 再次调用/stmtlog就能和antrance-ins正常通信了.
 * 另外, 如果antrance-ins上传了多个崩溃日志, 我们只会保留最新的那一份.
 * 3. 你可以使用/iscrash判断当前app是否发生了崩溃.
 * 注意, 由于历史遗留问题, 如果antrance-ins规定了app被kill前只能获取一次日志,
 * 调用/stmtlog获取一次以上的日志时会出错, 状态码500, 并返回一些相应的提示信息.
 */
public class Antrance extends AccessibilityService {

    /** AntranceServer端口 */
    private static final int myPort = 8624;
    /** AntranceIns地址 */
    private static final String address = "127.0.0.1:8625";

    /** notification channel */
    private static final String CHANNEL_NAME = "crash";
    private static final String CHANNEL_DESCRIPTION = "crash";
    private static final String CHANNEL_ID = "8";
    private static final int NOTIFICATION_ID = 8;
    /** 仅仅是为了打印时好区分不同的crash, 没什么用 */
    private static int crashCode = 1;


    /** 提供stmtlog和uitree服务, 单例 */
    class AntranceServer extends NanoHTTPD {
        /** 当前测试应用的最新log, json格式 */
        private String crashStmtLog = "";
        private final Object Lock = new Object();

        public AntranceServer() {
            super(myPort);
        }

        /**
         * 初始化, stmtLog置空, 测试开始时初始化(install,start阶段).
         */
        private void init() {
            synchronized (Lock) {
                crashStmtLog = "";
            }
        }

        /**
         * 根据是否有崩溃日志判断, 轻量级接口
         * @return 1 or 0
         */
        private int isCrash() {
            int flag = 0;
            synchronized (Lock) {
                if (!crashStmtLog.equals("")) {
                    flag = 1;
                }
            }
            return flag;
        }

        /**
         * 获取当前测试应用的日志.
         * <p> 1. 首先判断crashStmtLog是否为空, 不为空返回设置ans为crashStmtLog, 并将crashStmtLog置空, 跳转到4;
         *     为空跳转到2 <br>
         *     2. 向antrance ins请求日志, 请求失败抛出请求失败异常, 请求成功设置请求结果到ans, 跳转到3 <br>
         *     3. 由于历史遗留问题, 判断请求结果是否为-1, 是则抛出异常. 否则跳转到4 <br>
         *     4. 返回ans
         * @return json格式日志, 注意捕获异常判断是否发生了错误.
         */
        private String getStmtLog() throws IOException {
            // 1
            String ans = "";
            synchronized (Lock) {
                if (!crashStmtLog.equals("")) {
                    ans = crashStmtLog;
                    crashStmtLog = "";
                }
            }
            if (ans.equals("")) {
                // 2
                ans = getJson("http://"+address+"/stmtlog", "");
                // 3
                if (ans.equals("-1")) {
                    throw new RuntimeException("getStmtLog(): You can only get the stmtlog once!");
                }
            }
            // 4
            return ans;
        }

        private void setEventId(int id) throws IOException {
            getJson("http://"+address+"/seteventid", "id="+id);
        }

        /**
         * http get json
         * @param url 发送请求的URL
         * @return 响应结果, 非200抛异常
         */
        private String getJson(String url, String param) throws IOException {
            URL obj = new URL(url+"?"+param);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("Accept-Charset", "UTF-8");

            String res = "";
            try(BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                res = response.toString();
            }

            int responseCode = con.getResponseCode();
            Log.i("antrance", "getJson: GET Response Code :: " + responseCode);
            if (responseCode == HttpURLConnection.HTTP_OK) { // success
                return res;
            } else {
                throw new RuntimeException("getJson error code: " + responseCode + " error message: " + res);
            }
        }

        /**
         * 获取当前顶层activity的ui tree.
         * @return xml格式的ui tree, 注意捕获异常判断是否发生错误
         */
        private String getUiTree() {
            return UITree.dumpUITree(getWindows());
        }

        /**
         * 接收错误日志
         * @param log 错误日志
         */
        private void setCrashStmtLog(String log) {
            // 避免一切可能的阻塞
            Thread thread = new Thread(){
                @Override
                public void run() {
                    synchronized (Lock) {
                        crashStmtLog = log;
                    }
                }
            };
            thread.start();

            String contentText = log;
            if (contentText.length() >= 50) {
                contentText = contentText.substring(0, 50);
                contentText += "...";
            }
            NotificationCompat.Builder builder = new NotificationCompat.Builder(Antrance.this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_android_black_24dp)
                    .setContentTitle("antrance: app crashes: " + crashCode++)
                    .setContentText(contentText)
                    .setPriority(NotificationCompat.PRIORITY_MAX);
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(Antrance.this);
            // notificationId is a unique int for each notification that you must define
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }

        /**
         * 对object, 执行type类型的操作, 操作数为value.
         * <p> 具体介绍参考UITree.perform.
         */
        private void perform(String jsonData) {
            PerformAction performAction;
            try {
                Gson gson = new Gson();
                performAction = gson.fromJson(jsonData, PerformAction.class);
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
            UITree.perform(getWindows(), performAction.getType(),
                    performAction.getValue(), performAction.getObject(),
                    performAction.getPrefix());
        }

        /**
         * http server路由.
         * @param session NanoHttpd默认参数
         * @return 相应请求的返回值
         */
        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            if (Method.GET.equals(session.getMethod())) {
                switch (uri) {
                    case "/hello":
                        return NanoHTTPD.newFixedLengthResponse("hello");
                    case "/init":
                        Log.i("antrance", "get /init");
                        try {
                            init();
                        } catch (Exception e) {
                            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_HTML, e.toString());
                        }
                        return NanoHTTPD.newFixedLengthResponse("init successful");
                    case "/iscrash":
                        Log.i("antrance", "get /iscrash");
                        int flag = 0;
                        try {
                            flag = isCrash();
                        } catch (Exception e) {
                            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_HTML, e.toString());
                        }
                        return NanoHTTPD.newFixedLengthResponse(String.valueOf(flag));
                    case "/stmtlog":
                        Log.i("antrance", "get /stmtlog");
                        String stmtLog = "";
                        try {
                            stmtLog = getStmtLog();
                        } catch (Exception e) {
                            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_HTML, e.toString());
                        }
                        return NanoHTTPD.newFixedLengthResponse(stmtLog);
                    case "/uitree":
                        Log.i("antrance", "get /uitree");
                        String uiTree = "";
                        try {
                            uiTree = getUiTree();
                        } catch (Exception e) {
                            return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_HTML, e.toString());
                        }
                        return NanoHTTPD.newFixedLengthResponse(uiTree);
                    case "/seteventid":
                        Log.i("antrance", "get /seteventid");
                        Map<String, String> parms = session.getParms();
                        if (parms.containsKey("id")) {
                            String id = parms.get("id");
                            if (id != null) {
                                try {
                                    setEventId(Integer.parseInt(id));
                                } catch (Exception e) {
                                    return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_HTML, e.toString());
                                }
                            } else {
                                return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_HTML, "you should tell me the id");
                            }
                        } else {
                            return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_HTML, "you should tell me the id");
                        }
                        return NanoHTTPD.newFixedLengthResponse("seteventid successful");
                }
                return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML,
                        "please use /hello or /init or /iscrash or /stmtlog or /uitree or seteventid");
            } else if (Method.POST.equals(session.getMethod())) {
                if (uri.equals("/stmtlog")) {
                    Log.i("antrance", "post /stmtlog");
                    Map<String, String> data = new HashMap<String, String>();
                    String log;
                    try {
                        session.parseBody(data);
                        log = data.get("postData");
                    } catch (IOException | ResponseException e) {
                        e.printStackTrace();
                        return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_HTML,
                                "can not parse the post body");
                    }
                    try {
                        setCrashStmtLog(log);
                    } catch (Exception e) {
                        return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_HTML, e.toString());
                    }
                    return NanoHTTPD.newFixedLengthResponse("post error log successful");
                } else if (uri.equals("/perform")) {
                    Log.i("antrance", "post /perform");
                    Map<String, String> data = new HashMap<String, String>();
                    String event = "";
                    try {
                        session.parseBody(data);
                        event = data.get("postData");
                    } catch (IOException | ResponseException e) {
                        e.printStackTrace();
                        return NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_HTML,
                                "can not parse the post body");
                    }
                    try {
                        perform(event);
                    } catch (Exception e) {
                        return NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_HTML, e.toString());
                    }
                    return NanoHTTPD.newFixedLengthResponse("perform successful");
                }
                return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML,
                        "please use /stmtlog or /perform");
            } else {
                return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_HTML,
                        "please use get or post");
            }
        }
    }

    public AntranceServer antranceServer = new AntranceServer();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * 由于您必须先创建通知渠道，然后才能在 Android 8.0 及更高版本上发布任何通知，因此应在应用启动时立即执行这段代码.
     * 反复调用这段代码是安全的，因为创建现有通知渠道不会执行任何操作.
     */
    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance);
            channel.setDescription(CHANNEL_DESCRIPTION);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        try {
            antranceServer.start();
            Log.i("antrance", "antrance start on " + myPort);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e("antrance", "antrance start error");
        }
    }

    @Override
    public void onDestroy() {
        antranceServer.stop();
        Log.i("antrance", "antrance stop");
        super.onDestroy();
    }

//    private fun requestMyPermissions() {
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
//            //没有授权，编写申请权限代码
//            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
//        } else {
//            Log.d("hzy", "requestMyPermissions: 有写SD权限")
//        }
//        if (ContextCompat.checkSelfPermission(this,
//                        Manifest.permission.READ_EXTERNAL_STORAGE)
//                != PackageManager.PERMISSION_GRANTED) {
//            //没有授权，编写申请权限代码
//            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
//        } else {
//            Log.d("hzy", "requestMyPermissions: 有读SD权限")
//        }
//    }

}