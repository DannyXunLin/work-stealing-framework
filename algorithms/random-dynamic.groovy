import java.util.concurrent.ConcurrentLinkedQueue

def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    def groupTag = config.groupTag ?: 'default'        // <<< 新增:並行分支識別標籤,防撞名
    def cpuCores = config.cpuCores ?: null              // <<< 新增:CPU固定模式單一值;variable時為null
    def cpuPerWorker = config.cpuPerWorker              // <<< 新增:CPU不固定模式清單;fixed時為null
    def algorithmName = 'random-dynamic'
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

    def shuffledTasks = microBatches.clone()
    Collections.shuffle(shuffledTasks)
    ConcurrentLinkedQueue globalQueue = new ConcurrentLinkedQueue(shuffledTasks)

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def currentWorkerId = i + 1
        def thisCpu = cpuPerWorker ? cpuPerWorker[i].toString() : (cpuCores ?: res.requests.cpu)  // <<< 新增:決定本worker核心數,優先序同round-robin(此處索引用i)

        workerTasks["worker-${currentWorkerId}"] = {
            def podLabel = "rd-${BUILD_ID}-${groupTag}-${currentWorkerId}"   // <<< 改:插入${groupTag}防撞名;前綴rd-是與round-robin唯一字面差異

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
                // <<< 改:pod yaml 變動,與round-robin完全相同 ——
                //   (1) anti-affinity 改用 thesis-exp-group="${groupTag}" scoped 隔離(原因同round-robin
                //       的註解:階段一10條分支同時平行跑,全域label會讓30個pod在5台VM上互斥導致大量Pending)
                //   (2) cpu改用${thisCpu}
                node(podLabel) {
                    container('defects4j') {
                        def localLog = "/tmp/worker_${groupTag}_${currentWorkerId}_${BUILD_ID}.log"   // <<< 改:加${groupTag}_
                        sh "touch ${localLog}"

                        // <<< 改(sh呼叫合併,CHUNK_SIZE=5):原本每次poll()只拿1個task就呼叫一次sh,
                        //     實測(round-robin-1w console log時間戳比對)每次sh呼叫本身有~1.2秒固定
                        //     開銷。但random-dynamic是work-stealing(worker執行時動態搶globalQueue),
                        //     不能像round-robin那樣整批一次全部合併——那樣等於把「哪個worker拿到哪些
                        //     task」在第一個task都還沒開始跑之前就寫死,work-stealing的動態負載平衡會
                        //     完全消失。折衷做法:一次poll CHUNK_SIZE(5)個task組成一個chunk,同一個
                        //     chunk內串成一段shellScript只呼叫一次sh,呼叫次數降為1/5(理論可拿到
                        //     1-1/5=80%的開銷節省),同時每個worker仍然是「跑完一個chunk才回頭搶下一個
                        //     chunk」,保留跑得快的worker能搶到更多工作的動態特性,只是把負載平衡的
                        //     反應粒度從「每1個task」放粗成「每5個task」。
                        //     單一task逾時同round-robin.groovy的作法,改用bash自己的`timeout -k 10 3600`,
                        //     不再依賴外層Jenkins的timeout包住每個task——因為現在一個chunk內有多個task
                        //     共用一次sh呼叫,若不改用bash層級的timeout,單一task卡住會拖垮同一個chunk
                        //     裡後面還沒跑的task。外層timeout(time: chunk.size()*60)只是「每個task最多
                        //     60分鐘」疊加起來的最壞情況總和當保險上限,沒有放寬任何一個task實際可以卡
                        //     多久的保證。
                        def CHUNK_SIZE = 5
                        while (true) {
                            def chunk = []
                            for (int c = 0; c < CHUNK_SIZE; c++) {
                                def task = globalQueue.poll()
                                if (task == null) break
                                chunk.add(task)   // <<< 改:用.add()而非<<,repo裡其他地方(round-robin/lpt/spt/
                                                    //     hybrid/work-stealing等)清一色用.add()且都在真實build裡
                                                    //     跑過驗證;<<在這個repo唯一的先例(validate-baselines.groovy)
                                                    //     因SERIAL_WORKERS留空從沒被實際執行過,不確定Jenkins
                                                    //     script-security沙箱有沒有預先核准這個運算子,保守起見
                                                    //     用已驗證安全的寫法。
                            }
                            if (chunk.isEmpty()) break

                            def shellScript = """cd /workspace
export ANT_OPTS='${jvmOpts}'
"""
                            chunk.each { task ->
                                // <<< 改:log 行末新增 worker${currentWorkerId} 欄位(與lpt/spt同步處理,但
                                //     不加pred=,因random-dynamic不依賴EMA排序)。目的:globalQueue是
                                //     work-stealing poll,合併5個worker的finalLog後原本無法分辨哪幾行
                                //     屬於同一worker,也就無法驗證shuffle後每個worker實際分配到的任務
                                //     組合是否每次跑都不同(隨機性是否真的生效,而非固定seed或退化成
                                //     規律pattern)。加上後可用 grep ",worker${w}," 篩出同一worker的任務
                                //     序列直接比對。
                                // <<< 新增:log 行末再加 cpu${thisCpu} 欄位,原因同round-robin.groovy(見
                                //     該檔內對應註解)——CPU_MODE=variable時每個worker核心數不同,加上此
                                //     欄位讓每行任務都能獨立標明自己的執行核心數,不需額外查表對照。
                                shellScript += """start=\$(date +%s%3N)
timeout -k 10 3600 ant -Dtest.entry=${task.classes} test >/dev/null 2>&1 || true
end=\$(date +%s%3N)
duration=\$(awk "BEGIN {printf \\"%.3f\\", (\$end - \$start) / 1000}")
echo "${task.bug}:${task.id},\${duration},${algorithmName},worker${currentWorkerId},cpu${thisCpu}" >> ${localLog}
"""
                            }
                            timeout(time: chunk.size() * 60, unit: 'MINUTES') {
                                sh shellScript
                            }
                        }

                        sh "cp ${localLog} worker_log_${groupTag}_${currentWorkerId}_${BUILD_ID}.txt"   // <<< 改:加${groupTag}_
                        stash name: "log-${groupTag}-${currentWorkerId}-${BUILD_ID}", includes: "worker_log_${groupTag}_${currentWorkerId}_${BUILD_ID}.txt"   // <<< 改:stash加${groupTag}
                        sh "rm -f ${localLog} worker_log_${groupTag}_${currentWorkerId}_${BUILD_ID}.txt"   // <<< 改:加${groupTag}_
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
                unstash "log-${groupTag}-${w}-${BUILD_ID}"   // <<< 改
                sh "cat worker_log_${groupTag}_${w}_${BUILD_ID}.txt >> ${finalLog}"   // <<< 改
                sh "rm -f worker_log_${groupTag}_${w}_${BUILD_ID}.txt"   // <<< 改
            } catch (Exception e) {
                echo "WARNING: Failed to collect from worker ${w} (${groupTag}) - ${e.message}"   // <<< 改
            }
        }
    }
}
return this
