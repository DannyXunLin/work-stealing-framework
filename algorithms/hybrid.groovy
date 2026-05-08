def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    def algorithmName = 'hybrid'
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
    def groupedByBug = microBatches.groupBy { it.bug }
    def bugKeys = groupedByBug.keySet().toList()

    def workerAssignments = [:]
    for (int i = 0; i < workerCount; i++) { workerAssignments[i] = [] }
    bugKeys.eachWithIndex { bug, index -> workerAssignments[index % workerCount].add(bug) }
    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"
    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def workerIndex = i
        def currentWorkerId = workerIndex + 1
        def myBugs = workerAssignments[workerIndex]

        workerTasks["worker-${currentWorkerId}"] = {
            if (myBugs.isEmpty()) return

            myBugs.each { bug ->
                def tasksForThisBug = groupedByBug[bug]
                podTemplate(label: "hybrid-${bug}-${BUILD_ID}-${currentWorkerId}", yaml: """
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
                    node("hybrid-${bug}-${BUILD_ID}-${currentWorkerId}") {
                        container('defects4j') {
                            def bugResultFile = "hybrid_result_${BUILD_ID}_${currentWorkerId}_${bug}.txt"
                            def shellScript = """cd /workspace
export ANT_OPTS='${jvmOpts}'
"""
                            tasksForThisBug.each { task ->
                                shellScript += """start=\$(date +%s%3N)
ant -Dtest.entry=${task.classes} test >/dev/null 2>&1 || true
end=\$(date +%s%3N)
duration=\$(awk "BEGIN {printf \\"%.3f\\", (\$end - \$start) / 1000}")
echo "${task.bug}:${task.id},\${duration},${algorithmName}" >> ${bugResultFile}
"""
                            }
                            timeout(time: 60, unit: 'MINUTES') {
                                sh shellScript
                            }
                            stash name: "log-${currentWorkerId}-${bug}", includes: "${bugResultFile}"
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
        bugKeys.eachWithIndex { bug, index ->
            def workerId = (index % workerCount) + 1
            try {
                def bugResultFile = "hybrid_result_${BUILD_ID}_${workerId}_${bug}.txt"
                unstash "log-${workerId}-${bug}"
                sh "cat ${bugResultFile} >> ${finalLog}"
                sh "rm ${bugResultFile}"
            } catch (Exception e) {
                echo "WARNING: Failed to collect result for bug-${bug} worker-${workerId} - ${e.message}"
                def tasksForBug = groupedByBug[bug]
                tasksForBug.each { task ->
                    sh "echo '${task.bug}:${task.id},-1,${algorithmName}' >> ${finalLog}"
                }
            }
        }
    }
}
return this
