import java.util.concurrent.ConcurrentLinkedQueue

def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
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

    // 隨機打亂後放入 GlobalQueue，workers 動態搶任務
    def shuffledTasks = microBatches.clone()
    Collections.shuffle(shuffledTasks)
    ConcurrentLinkedQueue globalQueue = new ConcurrentLinkedQueue(shuffledTasks)

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def currentWorkerId = i + 1

        workerTasks["worker-${currentWorkerId}"] = {
            def podLabel = "rd-${BUILD_ID}-${currentWorkerId}"

            podTemplate(label: podLabel, yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: defects4j
    image: 140.134.27.100:5000/defects4j-mixed-het:latest
    imagePullPolicy: Always
    tty: true
    command: [cat]
    resources:
      requests: { cpu: "${res.requests.cpu}", memory: "${res.requests.memory}" }
      limits: { cpu: "${res.limits.cpu}", memory: "${res.limits.memory}" }
""") {
                node(podLabel) {
                    container('defects4j') {
                        def localLog = "/tmp/worker_${currentWorkerId}_${BUILD_ID}.log"
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
echo "${task.bug}:${task.id},\${duration},${algorithmName}" >> ${localLog}
"""
                            timeout(time: 60, unit: 'MINUTES') {
                                sh shellScript
                            }
                        }

                        // 全部跑完才 stash 一次
                        sh "cp ${localLog} worker_log_${currentWorkerId}_${BUILD_ID}.txt"
                        stash name: "log-${currentWorkerId}-${BUILD_ID}", includes: "worker_log_${currentWorkerId}_${BUILD_ID}.txt"
                        sh "rm -f ${localLog} worker_log_${currentWorkerId}_${BUILD_ID}.txt"
                    }
                }
            }
        }
    }
    parallel workerTasks

    node('built-in') {
        def finalLog = "${frameworkPath}/experiments/${algorithmName}/batch_durations_${BUILD_ID}.log"
        sh "touch ${finalLog}"
        for (int w = 1; w <= workerCount; w++) {
            try {
                unstash "log-${w}-${BUILD_ID}"
                sh "cat worker_log_${w}_${BUILD_ID}.txt >> ${finalLog}"
                sh "rm -f worker_log_${w}_${BUILD_ID}.txt"
            } catch (Exception e) {
                echo "WARNING: Failed to collect from worker ${w} - ${e.message}"
            }
        }
    }
}
return this
