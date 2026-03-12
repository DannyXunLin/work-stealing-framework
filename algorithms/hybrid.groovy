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
    image: defects4j-${bug}:latest
    imagePullPolicy: IfNotPresent
    tty: true
    command: [cat]
    resources:
      requests: { cpu: "${res.requests.cpu}", memory: "${res.requests.memory}" }
      limits: { cpu: "${res.limits.cpu}", memory: "${res.limits.memory}" }
""") {
                    node("hybrid-${bug}-${BUILD_ID}-${currentWorkerId}") {
                        container('defects4j') {
                            tasksForThisBug.each { task ->
                                def startTime = System.currentTimeMillis()
                                timeout(time: 60, unit: 'MINUTES') {
                                    def classesSpace = task.classes.replace(',', ' ')
                                    sh "cd /workspace && export ANT_OPTS='${jvmOpts}' && for test_class in ${classesSpace}; do ant -Dtest_class=\${test_class} test-single >/dev/null 2>&1 || true; done"
                                }
                                def duration = (System.currentTimeMillis() - startTime) / 1000.0
                                sh "echo '${task.bug}:${task.id},${duration},${algorithmName}' >> worker_${currentWorkerId}_${bug}.log"
                            }
                        }
                        stash name: "log-${currentWorkerId}-${bug}", includes: "worker_${currentWorkerId}_${bug}.log"
                    }
                } 
            }
        }
    }
    parallel workerTasks

    bugKeys.eachWithIndex { bug, index ->
        def workerId = (index % workerCount) + 1
        try {
            unstash "log-${workerId}-${bug}"
            sh "cat worker_${workerId}_${bug}.log >> ${frameworkPath}/experiments/${algorithmName}/batch_durations_${BUILD_ID}.log"
            sh "rm worker_${workerId}_${bug}.log"
        } catch (Exception e) {}
    }
}
return this
