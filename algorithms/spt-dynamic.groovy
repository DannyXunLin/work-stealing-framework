import java.util.concurrent.ConcurrentLinkedQueue

def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    def groupTag = config.groupTag ?: 'default'        // <<< 新增:防序列多worker數覆寫finalLog
    def cpuCores = config.cpuCores ?: null              // <<< 新增:CPU固定模式單一值;variable時為null
    def cpuPerWorker = config.cpuPerWorker              // <<< 新增:CPU不固定模式清單;fixed時為null
    def algorithmName = 'spt-dynamic'
    def frameworkPath = env.WORKSPACE

    def podSpecs = load "${frameworkPath}/config/PodSpecs.groovy"
    def res = podSpecs.getResources()
    def jvmOpts = podSpecs.getJvmOpts()

    def rawTasks = readFile(taskFile).split('\n').findAll { it.trim() }
    def microBatches = []
    def MAX_CLASSES = 30

    rawTasks.each { task ->
        def parts = task.split(':', 3)
        if (parts.size() >= 3 && parts[2].trim()) {
            def bug = parts[0]
            def batchId = parts[1]
            def classes = parts[2].split(',')
            if (classes.size() > MAX_CLASSES) {
                for (int i = 0; i < classes.size(); i += MAX_CLASSES) {
                    def end = Math.min(i + MAX_CLASSES, classes.size())
                    microBatches.add([bug: bug, id: "${batchId}-sub${(int)(i/MAX_CLASSES)}", classes: classes[i..<end].join(',')])
                }
            } else {
                microBatches.add([bug: bug, id: batchId, classes: parts[2]])
            }
        }
    }

    // 讀取歷史 EMA 資料預測執行時間
    echo "📊 讀取歷史資料計算 EMA..."
    // <<< 改:EMA 來源「只讀 round-robin 與 random-dynamic 兩個基準演算法、且只讀同 worker 數」。
    //     (1) 消除跨 worker 數污染:原 batch_durations_*.log 不分 worker 數,variable 模式下同一 task
    //         在不同 worker 數落在不同核心層級,混合會偏移 task 間的相對排序;限縮成 *-${workerCount}w.log 斷開。
    //     (2) 消除 lpt/spt 互讀污染並維持對等:只讀兩支 EMA-free 基準、不讀對方/自己,使 lpt-Nw 與
    //         spt-Nw 的 EMA 來源完全相同 = {round-robin-Nw, random-dynamic-Nw}。
    //     注意:基準 log 的「存在性 + 覆蓋率」驗證已前移到 Jenkinsfile「階段一·五:基準驗證」做 fail-fast
    //     前置(見 validate-baselines.groovy),一次驗完所有 worker 數。故此處不再內嵌防呆,避免跑到一半
    //     才整批作廢;能執行到這裡即代表驗證已通過,historyFiles 必為完整。
    //     寫法:兩條獨立路徑(dash-safe,不用 {} brace expansion,因 Jenkins sh 預設可能是 dash)。
    def historyFiles = sh(
        script: "ls ${frameworkPath}/experiments/round-robin/batch_durations_*-${workerCount}w.log ${frameworkPath}/experiments/random-dynamic/batch_durations_*-${workerCount}w.log 2>/dev/null || true",
        returnStdout: true
    ).split('\n').findAll { it.trim() }
    echo "📊 EMA 來源(基準演算法 @ ${workerCount}w,共 ${historyFiles.size()} 檔):${historyFiles.collect { it.split('/')[-1] }.join(', ')}"
    def emaMap = [:]
    def alpha = 0.3

    historyFiles.each { file ->
        if (file.trim()) {
            def lines = readFile(file).split('\n')
            lines.each { line ->
                def parts = line.split(',')
                if (parts.size() >= 2) {
                    def taskId = parts[0]
                    try {
                        def duration = parts[1].toDouble()
                        if (duration > 0) {
                            if (emaMap[taskId] == null) emaMap[taskId] = duration
                            else emaMap[taskId] = (alpha * duration) + ((1 - alpha) * emaMap[taskId])
                        }
                    } catch (Exception e) {}
                }
            }
        }
    }

    // 輕任務優先（升冪排序）
    microBatches.each { it.predictedTime = emaMap["${it.bug}:${it.id}"] ?: 10.0 }
    microBatches = sortByPredictedTimeAsc(microBatches)

    ConcurrentLinkedQueue globalQueue = new ConcurrentLinkedQueue(microBatches)

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def currentWorkerId = i + 1
        def thisCpu = cpuPerWorker ? cpuPerWorker[i].toString() : (cpuCores ?: res.requests.cpu)  // <<< 新增:決定本worker核心數,優先序同lpt

        workerTasks["worker-${currentWorkerId}"] = {
            def podLabel = "spt-${BUILD_ID}-${currentWorkerId}"   // 不動:序列無並行,worker編號不撞

            podTemplate(label: podLabel, yaml: """
apiVersion: v1
kind: Pod
metadata:
  labels:
    thesis-exp: worker
    thesis-exp-group: "${groupTag}"
spec:
  affinity:
    podAntiAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector:
          matchLabels:
            thesis-exp-group: "${groupTag}"
        topologyKey: "kubernetes.io/hostname"
  containers:
  - name: defects4j
    image: 140.134.27.100:5000/defects4j-mixed-het:latest
    imagePullPolicy: Always
    tty: true
    command: [cat]
    resources:
      requests: { cpu: "${thisCpu}", memory: "${res.requests.memory}" }
      limits: { cpu: "${thisCpu}", memory: "${res.limits.memory}" }
""") {
                // <<< 改:anti-affinity 從全域 thesis-exp:worker 改成 groupTag scoped(thesis-exp-group)。
                //     決策原因(#291 build 實測證實):lpt/spt 序列執行下,同一時刻只有一個分支存在,
                //     當時誤判為「全域與scoped無差異」,但忽略了「前一條分支的pod終止是異步的,不會在
                //     execute()返回的瞬間就從叢集消失」。全域label下,新分支的pod必須等前一條分支用同一
                //     全域label佔住的VM全部釋放完才能排程,wc=5時(用滿全部5台VM)完全沒有餘裕,觀察到
                //     "5 node(s) didn't match pod anti-affinity rules"反覆出現、Unschedulable長時間
                //     Pending,實測spt-dynamic-5w耗時245.879s,但任務內容本身只需84.861s,落差超過200秒
                //     是純排程等待,跟SPT排序邏輯無關。改成scoped後,同一groupTag內部的worker互斥保證不變
                //     (仍各佔一台VM,CPU配置仍有意義),但不同分支(不同groupTag)之間不再互斥,新分支可
                //     立即排程,不需等前一分支的pod完全終止。與round-robin/random-dynamic的修法一致。
                node(podLabel) {
                    container('defects4j') {
                        def localLog = "/tmp/worker_${currentWorkerId}_${BUILD_ID}.log"   // 不動
                        sh "touch ${localLog}"

                        while (true) {
                            def task = globalQueue.poll()
                            if (task == null) break

                            def shellScript = """cd /workspace
export ANT_OPTS='${jvmOpts}'
start=\$(date +%s%3N)
ant -Dtest.entry=${task.classes} test >/dev/null 2>&1 || true
end=\$(date +%s%3N)
duration=\$(awk "BEGIN {printf \\"%.3f\\", (\$end - \$start) / 1000}")
echo "${task.bug}:${task.id},\${duration},${algorithmName},worker${currentWorkerId},pred=${task.predictedTime}" >> ${localLog}
"""
                            // <<< 改:log 行末新增 worker${currentWorkerId} 與 pred=${task.predictedTime} 兩個欄位。
                            //     目的同lpt-dynamic:現有finalLog只有「task,duration,algorithmName」,合併5個
                            //     worker的log後無法分辨哪幾行屬於同一worker、也看不到排序當時的predictedTime,
                            //     導致SPT是否「輕任務優先」這個核心假設無法驗證。加上後可用
                            //     awk -F',' '{print $4}' 篩出同一worker的行,檢查該worker內部pred=值是否
                            //     符合SPT該有的非遞減單調性,且不受實際duration隨機波動干擾。
                            timeout(time: 60, unit: 'MINUTES') {
                                sh shellScript
                            }
                        }

                        sh "cp ${localLog} worker_log_${currentWorkerId}_${BUILD_ID}.txt"   // 不動
                        stash name: "log-${currentWorkerId}-${BUILD_ID}", includes: "worker_log_${currentWorkerId}_${BUILD_ID}.txt"   // 不動
                        sh "rm -f ${localLog} worker_log_${currentWorkerId}_${BUILD_ID}.txt"   // 不動
                    }
                }
            }
        }
    }
    parallel workerTasks

    node('built-in') {
        def finalLog = "${frameworkPath}/experiments/${algorithmName}/batch_durations_${BUILD_ID}_${groupTag}.log"   // <<< 改:加_${groupTag}
        sh "touch ${finalLog}"
        for (int w = 1; w <= workerCount; w++) {
            try {
                unstash "log-${w}-${BUILD_ID}"   // 不動
                sh "cat worker_log_${w}_${BUILD_ID}.txt >> ${finalLog}"   // 不動
                sh "rm -f worker_log_${w}_${BUILD_ID}.txt"   // 不動
            } catch (Exception e) {
                echo "WARNING: Failed to collect from worker ${w} - ${e.message}"
            }
        }
    }
}

@NonCPS
def sortByPredictedTimeAsc(list) {
    return list.sort { a, b -> a.predictedTime <=> b.predictedTime }
}

return this
