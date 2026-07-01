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

                        while (true) {
                            def task = globalQueue.poll()
                            if (task == null) break

                            def shellScript = """cd /workspace
export ANT_OPTS='${jvmOpts}'
start=\$(date +%s%3N)
ant -Dtest.entry=${task.classes} test >/dev/null 2>&1 || true
end=\$(date +%s%3N)
duration=\$(awk "BEGIN {printf \\"%.3f\\", (\$end - \$start) / 1000}")
echo "${task.bug}:${task.id},\${duration},${algorithmName},worker${currentWorkerId},cpu${thisCpu}" >> ${localLog}
"""
                            // <<< 改:log 行末新增 worker${currentWorkerId} 欄位(與lpt/spt同步處理,但不加
                            //     pred=,因random-dynamic不依賴EMA排序)。目的:globalQueue是work-stealing
                            //     poll,合併5個worker的finalLog後原本無法分辨哪幾行屬於同一worker,也就無法
                            //     驗證shuffle後每個worker實際分配到的任務組合是否每次跑都不同(隨機性是否
                            //     真的生效,而非固定seed或退化成規律pattern)。加上後可用
                            //     grep ",worker${w}," 篩出同一worker的任務序列直接比對。
                            // <<< 新增:log 行末再加 cpu${thisCpu} 欄位,原因同round-robin.groovy(見該檔
                            //     內對應註解)——CPU_MODE=variable時每個worker核心數不同,加上此欄位讓每
                            //     行任務都能獨立標明自己的執行核心數,不需額外查表對照。
                            timeout(time: 60, unit: 'MINUTES') {
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
