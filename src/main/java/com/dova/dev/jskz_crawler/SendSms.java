package com.dova.dev.jskz_crawler;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author luhaijun@jiashuangkuaizi.com
 * @ClassName: SendCoupon
 * @Description: 描述
 * @date 2016/8/8 20:01
 */
public class SendSms {

    static class BatchPkg{
        public String content;
        public List<String> phones;
        public String fileName; //原始文件的fileName
        public List<String> errors;
        public int times;
        public final int maxTimes;
        public BatchPkg(){
            this.content = "";
            this.phones = new ArrayList<>(100 * 1024);
            this.fileName = "";
            this.errors = new ArrayList<>(1024);
            this.times = 0;
            maxTimes = 3;
        }
        public boolean checkAndSwitch(){
            this.times++;
            if(errors.size() == 0 || this.times >= maxTimes){
                return false;
            }
            this.phones = this.errors;
            this.errors = new ArrayList<>(1024);
            return true;
        }
    }
    private CloseableHttpClient httpClient;

    private ExecutorService executorService;

    public SendSms() {
        PoolingHttpClientConnectionManager pcm = new PoolingHttpClientConnectionManager();
        pcm.setMaxTotal(4096);
        pcm.setDefaultMaxPerRoute(1024);
        httpClient = HttpClients.custom().setConnectionManager(pcm).setDefaultRequestConfig(getRequestConfig()).build();
        executorService = Executors.newFixedThreadPool(10);
    }

    private RequestConfig getRequestConfig() {
        return RequestConfig.custom()
                .setConnectionRequestTimeout(3000000)
                .setConnectTimeout(3000000)
                .setSocketTimeout(3000000).build();
    }

    public BatchPkg readBatchPkg(String fileName) {
        BufferedReader reader = null;
        BatchPkg batchPkg = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileName)));
            batchPkg = new BatchPkg();
            batchPkg.fileName = fileName;
            String tempString = null;
            // 一次读入一行，直到读入null为文件结束
            while ((tempString = reader.readLine()) != null) {
                // 显示行号
                tempString = tempString.trim();
                if(tempString.length() == 0){
                    continue;
                }
                if(tempString.startsWith("#")){
                    batchPkg.content = tempString.substring(1);
                }else {
                    batchPkg.phones.add(tempString);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e1) {
                }
            }
        }
        return batchPkg;
    }


    public void sendSms(String phones,String content) throws Exception {
        HttpPost post = new HttpPost("http://newsms.jiashuangkuaizi.com/sms/sendActive.do");
        List<NameValuePair> list = new ArrayList<NameValuePair>();
        list.add(new BasicNameValuePair("phones", phones));
        list.add(new BasicNameValuePair("content", content));
        post.setEntity(new UrlEncodedFormEntity(list, "utf-8"));
        CloseableHttpResponse response = httpClient.execute(post);
        if (response.getStatusLine().getStatusCode() / 100 == 2) {
            String ret = EntityUtils.toString(response.getEntity());
            System.out.println(ret);
        } else {
            throw new Exception("接口调用失败:" + (response != null ? response.getStatusLine().getStatusCode() : -1));
        }
    }


    public void sendBatchPkg(BatchPkg batchPkg){
        if(batchPkg == null){
            System.out.println("batchPkg is NULL:" + batchPkg.fileName);
            return;
        }
        if(Strings.isNullOrEmpty(batchPkg.content) || batchPkg.phones == null || batchPkg.phones.size() == 0){
            System.out.println("batchPkg's content or phones is empty:" + batchPkg.fileName);
            return;
        }
        System.out.println(String.format("Begin Send %s \nPhones:%s Content:[%s]",batchPkg.fileName, batchPkg.phones.size(), batchPkg.content));
        final String content = batchPkg.content;
        final Semaphore semaphore = new Semaphore(0);
        final AtomicLong succNum = new AtomicLong(0);
        final AtomicLong failNum = new AtomicLong(0);
        final int batchSize = 80;
        final AtomicInteger batchNum = new AtomicInteger(0);
        int lastIndex = 0;
        for (int i = 0;i < batchPkg.phones.size(); i++){
            if((i+1) % batchSize == 0 || batchPkg.phones.size() -1 == i){
                final List batchPhones = batchPkg.phones.subList(lastIndex, i+1);
                final String tmpPhones = Joiner.on(",").skipNulls().join(batchPhones);
                batchNum.addAndGet(1);
                this.executorService.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            //SendSms.this.sendSms(tmpPhones, content);
                            succNum.addAndGet(batchPhones.size());
                            //System.out.println(String.format("SendSucc size:%d phones:%s", batchPhones.size(), tmpPhones));
                        }catch (Exception e){
                            System.out.println(String.format("SendSmsFail content:[%s] phones:[%s]", content, tmpPhones));
                            failNum.addAndGet(batchPhones.size());
                            batchPkg.errors.addAll(batchPhones);
                            e.printStackTrace();
                        }
                        semaphore.release(1);
                    }
                });
                lastIndex = i+1;
            }
        }
        try {
            semaphore.acquire(batchNum.get());
            System.out.println(String.format("Finish Send %s \nSucc:%s Fail:%s Content:[%s]",batchPkg.fileName, succNum.get(), failNum.get(), content));
        }catch (Exception e){
            e.printStackTrace();
        }
        if(batchPkg.checkAndSwitch()){
            //如果有发送失败的就继续
            sendBatchPkg(batchPkg);
        }
    }


    public void sendFromFile(String filename){
        BatchPkg batchPkg = readBatchPkg(filename);
        sendBatchPkg(batchPkg);
    }
    @Test
    public void send0902(){
        String base = "/Users/liuzhendong/Documents/jskz_crawler/build/0902/";
        /*
        sendBatchPkg(readBatchPkg(base + "maocai.phones.origin"));
        sendBatchPkg(readBatchPkg(base + "maocai.phones.other"));
        sendBatchPkg(readBatchPkg(base + "chuancai.phones.origin"));
        sendBatchPkg(readBatchPkg(base + "chuancai.phones.other"));
        sendBatchPkg(readBatchPkg(base + "fruit.phones.origin"));
        sendBatchPkg(readBatchPkg(base + "fruit.phones.other"));
        */
    }

    @Test
    public void send0905(){
        String base = "/Users/liuzhendong/Documents/jskz_crawler/build/0905/";
        //sendBatchPkg(readBatchPkg(base + "test.sample.phones"));
        //sendBatchPkg(readBatchPkg(base + "couponpre_choice.phones"));
        //sendBatchPkg(readBatchPkg(base + "couponpre_mama.phones"));
        sendBatchPkg(readBatchPkg(base + "mama.phones.fail"));

    }

    @Test
    public void testFail()throws Exception{
        String phones  = "18811001536,13686877127,18329072121,13898617605,13801721366,18502757749,18801971475,15700076605,13566392920,13476316717,13636310264,13560753177,15021577679,18271393845,13260567674,13906829573,18357019971,13819732614,18582224176,18665945456,18905718676,18717123042,18101811123,15527973503,13818651301,18202891760,18721405436,18682317348,18603665789,13868683911,18868963797,18072725237,13816571192,13575456781,13601371335,15167132845,13817322845,18621026661,13407561511,13636307323,15088692803,15021678188,15004536789,13459999622,13868138045,17757109832,13777846892,13062698869,13070159538,18268196592,13917174784,15645831999,13482015993,13588707645,18611100714,18016266180,13764635770,15201621330,18626307744,15000205453,15824306579,15888822522,13801644533,18868708619,18612131003,15068887333,17721006733,15618804049,13612898905,18357114995,13916113568,18005649066,18811779689,18057780905,13762810018,13585666637,18868807185,15000892326,18969629228,18042402111";
        String content = "让胃远离伤害，杜绝地沟油、杜绝不卫生。【回家吃饭APP】吃家里的饭菜，饮食更健康。新用户一元试吃，满20减19，立戳立享用 http://t.cn/RcZnfDY";
        sendSms(phones,content);
    }


}
