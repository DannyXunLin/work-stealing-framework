def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
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

    // 輪詢分配：task i -> worker (i % workerCount)
    def workerAssignments = [:]
    for (int i = 0; i < workerCount; i++) { workerAssignments[i] = [] }
    microBatches.eachWithIndex { task, index -> workerAssignments[index % workerCount].add(task) }

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def workerIndex = i
        def currentWorkerId = workerIndex + 1
        def myTasks = workerAssignments[workerIndex]

        workerTasks["worker-${currentWorkerId}"] = {
            def podLabel = "rr-${BUILD_ID}-${currentWorkerId}"

            // 整個 Worker 的所有 Tasks 共用同一個 Pod
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
                        // 在同一個 Pod 內依序跑完所有分配到的 tasks
                        myTasks.each { task ->
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
                            stash name: "log-${currentWorkerId}-${task.bug}-${task.id}", includes: "${resultFile}"
                            sh "rm -f ${chunkResultFile}"
                        }
                        // myTasks 全部跑完後，Pod 才會在這裡銷毀
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
            def collected = false
            for (int w = 1; w <= workerCount; w++) {
                try {
                    def resultFile = "result_${task.bug}_${task.id}_${BUILD_ID}.txt"
                    unstash "log-${w}-${task.bug}-${task.id}"
                    sh "cat ${resultFile} >> ${finalLog}"
                    sh "rm ${resultFile}"
                    collected = true
                    break
                } catch (Exception e) {}
            }
            if (!collected) {
                echo "WARNING: Failed to collect result for ${task.bug}:${task.id}"
                sh "echo '${task.bug}:${task.id},-1,${algorithmName}' >> ${finalLog}"
            }
        }
    }
}
return this
