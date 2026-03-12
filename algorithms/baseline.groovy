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
                podTemplate(label: "base-${task.bug}-${BUILD_ID}-${currentWorkerId}", yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: defects4j
    image: defects4j-${task.bug}:latest
    imagePullPolicy: IfNotPresent
    tty: true
    command: [cat]
    resources:
      requests: { cpu: "${res.requests.cpu}", memory: "${res.requests.memory}" }
      limits: { cpu: "${res.limits.cpu}", memory: "${res.limits.memory}" }
""") {
                    node("base-${task.bug}-${BUILD_ID}-${currentWorkerId}") {
                        container('defects4j') {
                            def startTime = System.currentTimeMillis()
                            timeout(time: 60, unit: 'MINUTES') {
                                def classesSpace = task.classes.replace(',', ' ')
                                sh "cd /workspace && export ANT_OPTS='${jvmOpts}' && for test_class in ${classesSpace}; do ant -Dtest_class=\${test_class} test-single >/dev/null 2>&1 || true; done"
                            }
                            def duration = (System.currentTimeMillis() - startTime) / 1000.0
                            sh "echo '${task.bug}:${task.id},${duration},${algorithmName}' >> worker_${currentWorkerId}.log"
                        }
                        stash name: "log-${currentWorkerId}-${task.bug}-${task.id}", includes: "worker_${currentWorkerId}.log"
                    }
                }
            }
        }
    }
    parallel workerTasks

    microBatches.each { task ->
        for (int w = 1; w <= workerCount; w++) {
            try {
                unstash "log-${w}-${task.bug}-${task.id}"
                sh "cat worker_${w}.log >> ${frameworkPath}/experiments/${algorithmName}/batch_durations_${BUILD_ID}.log"
                sh "rm worker_${w}.log"
            } catch (Exception e) {}
        }
    }
}
return this
