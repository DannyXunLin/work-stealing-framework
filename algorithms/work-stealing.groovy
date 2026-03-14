import java.util.concurrent.ConcurrentLinkedQueue

def execute(Map config) {
    def taskFile = config.taskFile
    def workerCount = config.workerCount.toInteger()
    def algorithmName = 'work-stealing' 
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

    Collections.shuffle(taskChunks)
    ConcurrentLinkedQueue globalQueue = new ConcurrentLinkedQueue(taskChunks)

    sh "mkdir -p ${frameworkPath}/experiments/${algorithmName}"

    def workerTasks = [:]
    for (int i = 0; i < workerCount; i++) {
        def currentWorkerId = i + 1
        workerTasks["worker-${currentWorkerId}"] = {
            while (!globalQueue.isEmpty()) {
                def chunk = globalQueue.poll()
                if (chunk == null) break

                def bug = chunk.bug
                def tasksInChunk = chunk.tasks
                def podLabel = "ws-${bug}-${BUILD_ID}-${currentWorkerId}-${System.currentTimeMillis()}"
                
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
                            tasksInChunk.each { task ->
                                def startTime = System.currentTimeMillis()
                                timeout(time: 60, unit: 'MINUTES') {
                                    def classesSpace = task.classes.replace(',', ' ')
                                    sh "cd /workspace && export ANT_OPTS='${jvmOpts}' && for test_class in ${classesSpace}; do ant -Dtest_class=\${test_class} test-single >/dev/null 2>&1 || true; done"
                                }
                                def duration = (System.currentTimeMillis() - startTime) / 1000.0
                                sh "echo '${task.bug}:${task.id},${duration},${algorithmName}' > task_result.txt"
                                stash name: "res-${task.bug}-${task.id}-${BUILD_ID}", includes: "task_result.txt"
                            }
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
                unstash "res-${task.bug}-${task.id}-${BUILD_ID}"
                sh "cat task_result.txt >> ${finalLog}"
                sh "rm task_result.txt"
            } catch (Exception e) {}
        }
    }
}
return this
