def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    def groupTag = config.groupTag ?: 'default'        // <<< 新增:並行分支識別標籤,防同BUILD_ID多分支撞名
    def cpuCores = config.cpuCores ?: null              // <<< 新增:CPU固定模式單一值;variable時為null
    def cpuPerWorker = config.cpuPerWorker              // <<< 新增:CPU不固定模式清單;fixed時為null
    def algorithmName = 'round-robin'
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

    def workerAssignments = [:]
    for (int i = 0; i < workerCount; i++) { workerAssignments[i] = [] }
    microBatches.eachWithIndex { task, index -> workerAssignments[index % workerCount].add(task) }

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def workerIndex = i
        def currentWorkerId = workerIndex + 1
        def myTasks = workerAssignments[workerIndex]
        def thisCpu = cpuPerWorker ? cpuPerWorker[workerIndex].toString() : (cpuCores ?: res.requests.cpu)  // <<< 新增:決定本worker核心數。①不固定查清單第workerIndex個 → ②固定用cpuCores → ③都沒傳退回PodSpecs預設

        workerTasks["worker-${currentWorkerId}"] = {
            def podLabel = "rr-${BUILD_ID}-${groupTag}-${currentWorkerId}"   // <<< 改:插入${groupTag},防並行分支worker編號撞名

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
                // <<< 改:pod yaml 變動 ——
                //   (1) labels 新增 thesis-exp-group="${groupTag}",anti-affinity 的 matchLabels 改用這個 key
                //       (而非全域 thesis-exp:worker)。決策原因:階段一現在是「N個worker數 × 2個演算法」
                //       共10條分支同時平行跑(PARALLEL_WORKERS全部塞進同一個parallel區塊),若沿用全域
                //       label,30個worker pod會在僅5台VM上互斥,多出的會Pending、階段一被迫近似序列化、
                //       有撞6小時timeout風險。改成依groupTag(=演算法+worker數)隔離後:同一分支內部仍強制
                //       各worker佔不同VM(CPU_MODE的核心配置才有意義,此保證不變);不同分支之間允許共用VM,
                //       残留風險是「跨分支、方向不固定」的噪音,不是針對特定演算法的系統性偏差。
                //       (lpt/spt不用做同樣調整:序列執行,同時只有一分支存在,全域與scoped label無差異)
                //   (2) requests.cpu / limits.cpu 兩處由 ${res.*} 改成 ${thisCpu},讓CPU_MODE生效
                node(podLabel) {
                    container('defects4j') {
                        def localLog = "/tmp/worker_${groupTag}_${currentWorkerId}_${BUILD_ID}.log"   // <<< 改:加${groupTag}_防並行同機/tmp撞名
                        sh "touch ${localLog}"

                        // <<< 改(小規模驗證用):myTasks 原本每個task各自呼叫一次sh,實測(build302
                        //     round-robin-1w console log時間戳比對)每次sh呼叫本身有~1.2秒固定開銷,
                        //     1214個task跑一次worker就是~1457秒,佔該分支總耗時約26%。round-robin是
                        //     靜態分配(index % workerCount,myTasks在迴圈開始前就已經定案,不像
                        //     random-dynamic/lpt/spt要邊跑邊向共用佇列要下一個task),所以可以安全地把
                        //     myTasks全部串成同一段shellScript,整個worker只呼叫一次sh,不影響任何
                        //     分配結果。log行格式(含worker${currentWorkerId}/cpu${thisCpu}欄位,理由
                        //     見下方,與lpt/spt/random-dynamic對齊)不變,只是改成逐task append進同一個
                        //     字串,最後一次寫入。
                        def shellScript = """cd /workspace
export ANT_OPTS='${jvmOpts}'
"""
                        myTasks.each { task ->
                            // <<< 改:log 行末新增 worker${currentWorkerId} 欄位(與lpt/spt/random-dynamic
                            //     同步處理)。round-robin本身是靜態分配(index % workerCount),理論上不需要
                            //     靠這欄位排錯,但加上後可肉眼核對「任務序號是否確實依照 i % N 規律輪流分配」
                            //     ,且四個演算法的finalLog格式保持一致,方便後續用同一套awk/grep腳本分析。
                            // <<< 新增:log 行末再加 cpu${thisCpu} 欄位。決策原因:CPU_MODE=variable時,每個
                            //     worker核心數不同,但log行原本完全沒有記錄該worker當時配置了幾核心,事後
                            //     分析時無法直接從log內容判斷該筆任務是在哪個核心數下執行,只能另外對照
                            //     Jenkinsfile的CPU_VARIABLE_CORES參數跟worker編號的映射關係,容易搞混或忘記。
                            //     加上後每一行都能獨立標明自己的執行核心數,不需要額外查表。fixed模式下
                            //     thisCpu對五個worker都相同,這欄位變成常數,不影響任何分析,只是多一個可
                            //     以肉眼核對的欄位,不會造成負面影響。
                            shellScript += """start=\$(date +%s%3N)
timeout -k 10 3600 ant -Dtest.entry=${task.classes} test >/dev/null 2>&1 || true
end=\$(date +%s%3N)
duration=\$(awk "BEGIN {printf \\"%.3f\\", (\$end - \$start) / 1000}")
echo "${task.bug}:${task.id},\${duration},${algorithmName},worker${currentWorkerId},cpu${thisCpu}" >> ${localLog}
"""
                        }
                        // <<< 改:單一task逾時改用bash自己的`timeout -k 10 3600`(3600秒=60分鐘,10秒後
                        //     還沒死就補SIGKILL),不再依賴外層Jenkins的timeout(time:60,unit:'MINUTES')
                        //     包住每個task呼叫——原本那個包法是包住每一次sh呼叫,現在整個worker只剩一次
                        //     sh呼叫,若不改用bash層級的timeout,單一task卡住會讓整段shellScript(含後面
                        //     所有還沒跑的task)一起被判定逾時、整批陣亡,而不是只犧牲那一個task。
                        //     外層這裡的timeout(time: myTasks.size()*60, unit:'MINUTES')只是抓「每個task
                        //     最多60分鐘」疊加起來的最壞情況總和當保險上限,沒有放寬任何一個task實際可以
                        //     卡多久的保證(真正的60分鐘上限由上面bash的timeout保證),純粹是避免bash腳本
                        //     本身在個別ant呼叫之外的地方卡死時,還有一層Jenkins端的兜底。
                        timeout(time: myTasks.size() * 60, unit: 'MINUTES') {
                            sh shellScript
                        }

                        sh "cp ${localLog} worker_log_${groupTag}_${currentWorkerId}_${BUILD_ID}.txt"   // <<< 改:檔名加${groupTag}_
                        stash name: "log-${groupTag}-${currentWorkerId}-${BUILD_ID}", includes: "worker_log_${groupTag}_${currentWorkerId}_${BUILD_ID}.txt"   // <<< 改:stash名稱加${groupTag}防覆寫
                        sh "rm -f ${localLog} worker_log_${groupTag}_${currentWorkerId}_${BUILD_ID}.txt"   // <<< 改:檔名加${groupTag}_
                    }
                }
            }
        }
    }
    parallel workerTasks

    node('built-in') {
        def finalLog = "${frameworkPath}/experiments/${algorithmName}/batch_durations_${BUILD_ID}_${groupTag}.log"   // <<< 改:加_${groupTag}讓各分支寫各自log不覆寫;檔名仍以batch_durations_開頭,EMA萬用字元撈得到
        sh "touch ${finalLog}"
        for (int w = 1; w <= workerCount; w++) {
            try {
                unstash "log-${groupTag}-${w}-${BUILD_ID}"   // <<< 改
                sh "cat worker_log_${groupTag}_${w}_${BUILD_ID}.txt >> ${finalLog}"   // <<< 改
                sh "rm -f worker_log_${groupTag}_${w}_${BUILD_ID}.txt"   // <<< 改
            } catch (Exception e) {
                echo "WARNING: Failed to collect from worker ${w} (${groupTag}) - ${e.message}"   // <<< 改:加(${groupTag})辨識分支
            }
        }
    }
}
return this
