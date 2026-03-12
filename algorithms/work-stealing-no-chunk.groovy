import java.util.concurrent.ConcurrentLinkedQueue

def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    // 【修改點 1】更改演算法名稱，確保 log 輸出在不同資料夾，方便事後對比
    def algorithmName = 'work-stealing-no-chunk' 
    def frameworkPath = env.WORKSPACE

    def podSpecs = load "${frameworkPath}/config/PodSpecs.groovy"
    def res = podSpecs.getResources()
    def jvmOpts = podSpecs.getJvmOpts()

    // 1. 讀取任務
    def rawTasks = readFile(taskFile).split('\n').findAll{ it.trim() }
    def microBatches = []
    def MAX_CLASSES = 30
    
    // 2. 微批次化 (Micro-batching) - 這裡保留，因為要證明是 Chunking 的功勞，其他變數必須控制一樣
    rawTasks.each { task ->
        def parts = task.split(':', 3)
        if (parts.size() >= 3 && parts[2].trim()) {
            def bug = parts[0]
            def batchId = parts[1]
            def classes = parts[2].split(',')
            if (classes.size() > MAX_CLASSES) {
                for (int i = 0; i < classes.size(); i += MAX_CLASSES) {
                    def end = Math.min(i + MAX_CLASSES, classes.size())
                    microBatches.add([bug: bug, id: "${batchId}-sub${i/MAX_CLASSES}", classes: classes[i..<end].join(',')])
                }
            } else {
                microBatches.add([bug: bug, id: batchId, classes: parts[2]])
            }
        }
    }

    // 【修改點 2】完全移除 Chunking 邏輯
    // 直接把微批次洗牌後放入全域佇列
    def shuffledTasks = microBatches.clone()
    Collections.shuffle(shuffledTasks)
    ConcurrentLinkedQueue globalQueue = new ConcurrentLinkedQueue(shuffledTasks)

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    // 3. 虛擬工作者動態拉取 (沒有容器重用，每個任務開一個 Pod)
    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def currentWorkerId = i + 1
        workerTasks["worker-${currentWorkerId}"] = {
            while (!globalQueue.isEmpty()) {
                // 【修改點 3】每次拉取的是「單一任務」，而不是一包 Chunk
                def task = globalQueue.poll()
                if (task == null) break

                def bug = task.bug
                // 修改 Label 避免名稱衝突
                def podLabel = "ws-nc-${bug}-${BUILD_ID}-${currentWorkerId}-${System.currentTimeMillis()}"
                
                // 為「這一個任務」單獨啟動一個 Pod
                podTemplate(label: podLabel, yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: defects4j
    image: defects4j-${bug}:latest
    imagePullPolicy: IfNotPresent
    tty: true
    command: [cat]
    resources:
      requests: { cpu: "${res.requests.cpu}", memory: "${res.requests.memory}" }
      limits: { cpu: "${res.limits.cpu}", memory: "${res.limits.memory}" }
""") {
                    node(podLabel) {
                        container('defects4j') {
                            // 【修改點 4】不需要 for 迴圈遍歷 Chunk 了，直接執行該任務
                            def startTime = System.currentTimeMillis()
                            timeout(time: 60, unit: 'MINUTES') {
                                def classesSpace = task.classes.replace(',', ' ')
                                sh "cd /workspace && export ANT_OPTS='${jvmOpts}' && for test_class in ${classesSpace}; do ant -Dtest_class=\${test_class} test-single >/dev/null 2>&1 || true; done"
                            }
                            def duration = (System.currentTimeMillis() - startTime) / 1000.0
                            
                            // 紀錄這一個任務的執行時間
                            sh "echo '${task.bug}:${task.id},${duration},${algorithmName}' > task_result.txt"
                            stash name: "res-${task.bug}-${task.id}-${BUILD_ID}", includes: "task_result.txt"
                        }
                    }
                } 
            } 
        }
    }
    parallel workerTasks

    // 4. 聚合結果
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
