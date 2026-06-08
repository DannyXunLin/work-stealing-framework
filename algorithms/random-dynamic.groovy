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

            // 整個 Worker 只建立一個 Pod
            podTemplate(label: podLabel, yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: defects4j
    image: 140.134.27.100:5000/defects4j-lang-bug1:latest
    imagePullPolicy: Always
    tty: true
    command: [cat]
    resources:
      requests: { cpu: "${res.requests.cpu}", memory: "${res.requests.memory}" }
      limits: { cpu: "${res.limits.cpu}", memory: "${res.limits.memory}" }
""") {
                node(podLabel) {
                    container('defects4j') {
                        // 在同一個 Pod 內持續從 GlobalQueue 搶任務直到空為止
                        while (true) {
                            def task = globalQueue.poll()
                            if (task == null) break

                            def chunkResultFile = "/home/jenkins/agent/workspace/work-stealing-comparison/chunk_result_${BUILD_ID}_${currentWorkerId}_${task.id.replace('-', '_')}.txt"
                            def shellScript = """cd /workspace
export ANT_OPTS='${jvmOpts}'
start=\$(date +%s%3N)
ant -Dtest.entry=${task.classes} test >/dev/null 2>&1 || true
end=\$(date +%s%3N)
duration=\$(awk "BEGIN {printf \\"%.3f\\", (\$end - \$start) / 1000}")
echo "${task.bug}:${task.id},\${duration},${algorithmName}" >> ${chunkResultFile}
"""
                            timeout(time: 60, unit: 'MINUTES') {
                                sh shellScript
                            }
                            def resultFile = "result_${task.bug}_${task.id}_${BUILD_ID}.txt"
                            sh "grep '^${task.bug}:${task.id},' ${chunkResultFile} | tail -1 > ${resultFile}"
                            stash name: "res-${task.bug}-${task.id}-${BUILD_ID}", includes: "${resultFile}"
                            sh "rm -f ${chunkResultFile}"
                        }
                        // Queue 清空後，Pod 才在這裡銷毀
                    }
                }
            }
        }
    }
    parallel workerTasks

    node('built-in') {
        def finalLog = "${frameworkPath}/experiments/${algorithmName}/batch_durations_${BUILD_ID}.log"
        sh "touch ${finalLog}"
        microBatches.each { task ->
            try {
                def resultFile = "result_${task.bug}_${task.id}_${BUILD_ID}.txt"
                unstash "res-${task.bug}-${task.id}-${BUILD_ID}"
                sh "cat ${resultFile} >> ${finalLog}"
                sh "rm ${resultFile}"
            } catch (Exception e) {
                echo "WARNING: Failed to collect result for ${task.bug}:${task.id} - ${e.message}"
                sh "echo '${task.bug}:${task.id},-1,${algorithmName}' >> ${finalLog}"
            }
        }
    }
}
return this
