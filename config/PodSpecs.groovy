def getResources() {
    // <<< 改:limits.memory 從 4Gi 拉到 6Gi。
    //     決策原因:jvmOpts 設定 Xmx3g + MaxMetaspaceSize512m = JVM自身預算3584Mi,對原本4096Mi
    //     的容器上限只留512Mi餘裕給thread stack/JIT code cache/native memory(Mockito動態生成
    //     proxy class對metaspace/native memory壓力較重),偏緊,有機會被判定超過limits而OOMKilled、
    //     腰斷正在寫的log。VM已升級為8核心+8192MB,扣除k3s agent+OS開銷後,留給單一Pod(一VM一Pod
    //     設計,不需除以併發數)的可用上限約7000Mi,6Gi在此範圍內仍留約2Gi系統層餘裕,不會逼近VM總量。
    //     新餘裕(6144-3584=2560Mi)約為原本(512Mi)的5倍。
    //     requests.memory 維持2Gi不動:它只是排程用的資源宣告基準,不是觸發OOMKill的門檻,跟此次
    //     要解的問題無關。
    // <<< 注意:requests.cpu/limits.cpu 這兩個值目前是死碼,不會被四支演算法檔讀到。
    //     四支檔案的CPU實際來源一律是Jenkinsfile傳入的cpuCores/cpuPerWorker(由CPU_MODE決定),
    //     thisCpu計算邏輯 `cpuPerWorker ? ... : (cpuCores ?: res.requests.cpu)` 在CPU_MODE
    //     恆為fixed或variable的前提下,永遠不會落到res.requests.cpu這個fallback;res.limits.cpu
    //     則完全沒有任何呼叫路徑(pod yaml的requests/limits.cpu兩處都直接用${thisCpu})。
    //     若要調整CPU配置,請改Jenkinsfile的CPU_FIXED_CORES/CPU_VARIABLE_CORES,改這裡的cpu值無效。
    return [requests: [cpu: "1", memory: "2Gi"], limits: [cpu: "2", memory: "6Gi"]]
}
def getJvmOpts() {
    return "-Xmx3g -XX:MaxMetaspaceSize=512m"
}
return this
