import java.util.concurrent.ConcurrentLinkedQueue

def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    def algorithmName = 'warm-pool'
    def frameworkPath = env.WORKSPACE

    def podSpecs = load "${frameworkPath}/config/PodSpecs.groovy"
    def res = podSpecs.getResources()
    def jvmOpts = podSpecs.getJvmOpts()

    // ── 1. 讀取 Task 清單（與 work-stealing 完全相同）──
    def rawTasks = readFile(taskFile).split('\n').findAll { it.trim() }
    def microBatches = []

    rawTasks.each { task ->
        def parts = task.split(':', 3)
        if (parts.size() >= 3 && parts[2].trim()) {
            microBatches.add([bug: parts[0], id: parts[1], classes: parts[2]])
        }
    }

    // ── 2. Chunk=3 打包（與 work-stealing 完全相同）──
    // 確保唯一變數是 Pod 生命週期，其他條件全部一致
    def CHUNK_SIZE = 3
    def groupedByBug = microBatches.groupBy { it.bug }
    def taskChunks = []

    groupedByBug.each { bug, batches ->
        def currentChunk = []
        batches.each { batch ->
            currentChunk.add(batch)
            if (currentChunk.size() >= CHUNK_SIZE) {
                taskChunks.add([bug: bug, tasks: currentChunk.clone()])
                currentChunk = []
            }
        }
        if (currentChunk.size() > 0) taskChunks.add([bug: bug, tasks: currentChunk])
    }

    // ── 3. 洗牌後放進全域 Queue（與 work-stealing 相同）──
    Collections.shuffle(taskChunks)
    ConcurrentLinkedQueue globalQueue = new ConcurrentLinkedQueue(taskChunks)

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    // ── 4. 核心差異：每個 Worker 只開一次 Pod，Pod 持續從 Queue 動態拉取 Chunk ──
    // work-stealing：每個 Chunk 開一個新 Pod，跑完關掉
    // warm-pool：Pod 事先開好，持續存活，直到 Queue 空為止
    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def currentWorkerId = i + 1
        workerTasks["worker-${currentWorkerId}"] = {

            // Pod 只開一次（Warm Pool 核心）
            def podLabel = "wp-${BUILD_ID}-${currentWorkerId}"

            podTemplate(label: podLabel, yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: defects4j
    image: 192.168.56.10:5000/defects4j-lang-bug1:latest
    imagePullPolicy: Always
    tty: true
    command: [cat]
    resources:
      requests: { cpu: "${res.requests.cpu}", memory: "${res.requests.memory}" }
      limits: { cpu: "${res.limits.cpu}", memory: "${res.limits.memory}" }
""") {
                node(podLabel) {
                    container('defects4j') {

                        // Pod 持續從 Queue 拉 Chunk，跑完繼續拉，不關閉
                        while (!globalQueue.isEmpty()) {
                            def chunk = globalQueue.poll()
                            if (chunk == null) break

                            def tasksInChunk = chunk.tasks
                            tasksInChunk.each { task ->
                                def startTime = System.currentTimeMillis()
                                timeout(time: 60, unit: 'MINUTES') {
                                    // for loop 攤銷 JVM 啟動成本（與 work-stealing 相同）
                                    // 修正：使用正確的 ant target（test）與參數（test.entry）
                                    def classesSpace = task.classes.replace(',', ' ')
                                    sh "cd /workspace && export ANT_OPTS='${jvmOpts}' && for test_class in ${classesSpace}; do ant -Dtest.entry=\${test_class} test >/dev/null 2>&1 || true; done"
                                }
                                def duration = (System.currentTimeMillis() - startTime) / 1000.0
                                sh "echo '${task.bug}:${task.id},${duration},${algorithmName}' > task_result.txt"
                                stash name: "res-${task.bug}-${task.id}-${BUILD_ID}", includes: "task_result.txt"
                            }
                        }
                        // Queue 空了，Pod 自然結束
                    }
                }
            }
        }
    }
    parallel workerTasks

    // ── 5. 收集結果（與 work-stealing 完全相同）──
    node('built-in') {
        def finalLog = "${frameworkPath}/experiments/${algorithmName}/batch_durations_${BUILD_ID}.log"
        sh "touch ${finalLog}"
        microBatches.each { task ->
            try {
                unstash "res-${task.bug}-${task.id}-${BUILD_ID}"
                sh "cat task_result.txt >> ${finalLog}"
                sh "rm task_result.txt"
            } catch (Exception e) {}
        }
    }
}
return this
