import java.util.concurrent.ConcurrentLinkedQueue

def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    def algorithmName = 'warm-pool'
    def frameworkPath = env.WORKSPACE

    def podSpecs = load "${frameworkPath}/config/PodSpecs.groovy"
    def res = podSpecs.getResources()
    def jvmOpts = podSpecs.getJvmOpts()

    // ── 1. 讀取 Task 清單，把每個 batch 拆開成單一測試類別 ──
    // work-stealing 是以 batch 為單位，warm-pool 改成以單一類別為單位
    // 這樣 113 個類別可以各自進 Queue，讓 4 個 Worker 動態搶著跑
    def rawTasks = readFile(taskFile).split('\n').findAll { it.trim() }
    def microBatches = []

    rawTasks.each { task ->
        def parts = task.split(':', 3)
        if (parts.size() >= 3 && parts[2].trim()) {
            def bug = parts[0]
            def batchId = parts[1]
            parts[2].split(',').eachWithIndex { className, idx ->
                microBatches.add([bug: bug, id: "${batchId}-c${idx}", classes: className.trim()])
            }
        }
    }

    // ── 2. 洗牌後放進全域 Queue（與 work-stealing 相同）──
    Collections.shuffle(microBatches)
    ConcurrentLinkedQueue globalQueue = new ConcurrentLinkedQueue(microBatches)

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    // ── 3. 核心差異：每個 Worker 只開一次 Pod，Pod 持續從 Queue 拉 Task ──
    // work-stealing 是每個 Chunk 開一個新 Pod，跑完關掉
    // warm-pool 是 Pod 事先開好，一直活著，直到 Queue 空為止
    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def currentWorkerId = i + 1
        workerTasks["worker-${currentWorkerId}"] = {

            // Pod 只開一次（Warm Pool 核心）
            def podLabel = "wp-${BUILD_ID}-${currentWorkerId}-${System.currentTimeMillis()}"

            podTemplate(label: podLabel, yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: defects4j
    image: defects4j-lang-bug1:latest
    imagePullPolicy: IfNotPresent
    tty: true
    command: [cat]
    resources:
      requests: { cpu: "${res.requests.cpu}", memory: "${res.requests.memory}" }
      limits: { cpu: "${res.limits.cpu}", memory: "${res.limits.memory}" }
""") {
                node(podLabel) {
                    container('defects4j') {

                        // Pod 持續從 Queue 拉 Task，跑完繼續拉，不關閉
                        while (!globalQueue.isEmpty()) {
                            def task = globalQueue.poll()
                            if (task == null) break

                            def startTime = System.currentTimeMillis()
                            timeout(time: 60, unit: 'MINUTES') {
                                // 每個 Task 只有一個類別，直接跑（不需要 for loop）
                                sh "cd /workspace && export ANT_OPTS='${jvmOpts}' && ant -Dtest_class=${task.classes} test-single >/dev/null 2>&1 || true"
                            }
                            def duration = (System.currentTimeMillis() - startTime) / 1000.0
                            // stash 檔名與 work-stealing 保持一致
                            sh "echo '${task.bug}:${task.id},${duration},${algorithmName}' > task_result.txt"
                            stash name: "res-${task.bug}-${task.id}-${BUILD_ID}", includes: "task_result.txt"
                        }
                        // Queue 空了，Pod 自然結束
                    }
                }
            }
        }
    }
    parallel workerTasks

    // ── 4. 收集結果（與 work-stealing 完全相同）──
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
