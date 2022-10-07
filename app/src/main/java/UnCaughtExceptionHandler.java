import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 应用崩溃时回调, 记录崩溃日志 */
class UnCaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
    /** Antrance地址 */
    private static final String address = "127.0.0.1:8624";

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        // android不允许网络传输在主线程进行, 为了保险我们新开个线程进行传输
        // 由于使用了匿名内部类, 在插桩时别忘了UnCaughtExceptionHandler$1
        Thread thread = new Thread(){
            @Override
            public void run() {
                Log.i("antranceIns", "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
                e.printStackTrace();
                Log.i("antranceIns", "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
                String jsonLog = AntranceIns.getLogJson(false, e);
                Log.i("antranceIns", "====================caught a crash!====================");
                Log.i("antranceIns", "====================post stmt log====================");
                String ans = postJson("http://"+address+"/stmtlog",
                        jsonLog);
                Log.i("antranceIns", "post response: " + ans);
                Log.i("antranceIns", "====================post finished====================");
            }
        };
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interruptedException) {
            interruptedException.printStackTrace();
        }
    }

    /**
     * http post json
     * @param url 发送请求的URL
     * @param jsonStr 请求内容
     * @return 响应结果
     */
    private static String postJson(String url, String jsonStr) {
        try {
            HttpURLConnection con = (HttpURLConnection)((new URL(url)).openConnection());
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json; utf-8");
            con.setRequestProperty("Accept", "application/json");
            con.setDoOutput(true);
            try(OutputStream os = con.getOutputStream()) {
                byte[] input = jsonStr.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            String res = "";
            try(BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                res =  response.toString();
            }
            int responseCode = con.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return res;
            } else {
                return "post json error code: " + responseCode + " error message: " + res;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "post json error";
    }
}
