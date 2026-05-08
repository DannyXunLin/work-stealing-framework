def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    def algorithmName = 'baseline'
    def frameworkPath = env.WORKSPACE
    def podSpecs = load "${frameworkPath}/config/PodSpecs.groovy"
    def res = podSpecs.getResources()
    def jvmOpts = podSpecs.getJvmOpts()
    def rawTasks = readFile(taskFile).split('\n').findAll{ it.trim() }
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
                    microBatches.add([bug: bug, id: "${batchId}-sub${i/MAX_CLASSES}", classes: classes[i..<end].join(',')])
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

        workerTasks["worker-${currentWorkerId}"] = {
            myTasks.each { task ->
                podTemplate(label: "base-${task.bug}-${BUILD_ID}-${currentWorkerId}-${System.currentTimeMillis()}", yaml: """
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
                    node("base-${task.bug}-${BUILD_ID}-${currentWorkerId}-${System.currentTimeMillis()}") {
                        container('defects4j') {
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
                unstash "log-${currentWorkerId}-${task.bug}-${task.id}"
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
