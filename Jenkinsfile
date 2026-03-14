pipeline {
    agent any
    parameters {
        // 1. 在下拉選單最後面加上 'ALL'
        choice(name: 'ALGORITHM', choices: ['hybrid', 'baseline', 'work-stealing-no-chunk', 'work-stealing', 'ema-dynamic', 'ALL'], description: '選擇演算法')
        string(name: 'WORKER_COUNT', defaultValue: '1', description: 'Worker 數量')
        string(name: 'TASK_FILE', defaultValue: '/var/lib/jenkins/batch_tasks_fine_grained.txt', description: '任務列表檔案路徑')
        booleanParam(name: 'CLEAN_REPORTS', defaultValue: true, description: '執行前清理舊報告')
    }
    options {
        timeout(time: 6, unit: 'HOURS') 
        timestamps()
    }
    stages {
        stage('準備與執行') {
            steps {
                script {
                    env.FRAMEWORK_PATH = env.WORKSPACE 
                    
                    // 2. 判斷要跑哪些演算法
                    def algosToRun = []
                    if (params.ALGORITHM == 'ALL') {
                        // 注意順序：ema-dynamic 放最後面，讓它可以吃前面的歷史紀錄
                        algosToRun = ['hybrid', 'baseline', 'work-stealing-no-chunk', 'work-stealing', 'ema-dynamic']
                    } else {
                        algosToRun = [params.ALGORITHM]
                    }

                    // 3. 用迴圈依序執行
                    algosToRun.each { algoName ->
                        echo "======================================================="
                        echo "🚀 準備執行演算法: ${algoName} (Worker 數量: ${params.WORKER_COUNT})"
                        echo "======================================================="
                        
                        sh "mkdir -p ${env.FRAMEWORK_PATH}/experiments/${algoName}"
                        if (params.CLEAN_REPORTS) {
                            sh "rm -rf ${env.FRAMEWORK_PATH}/experiments/${algoName}/* || true"
                        }
                        def algorithmFile = "${env.FRAMEWORK_PATH}/algorithms/${algoName}.groovy"
                        def algorithm = load algorithmFile
                        
                        echo "⏱️ [System] 開始測量真實執行時間 (Wall Time)..."
                        long startTime = System.currentTimeMillis()
                        
                        // 執行演算法
                        algorithm.execute(taskFile: params.TASK_FILE, workerCount: params.WORKER_COUNT.toInteger())
                        
                        long endTime = System.currentTimeMillis()
                        def duration = (endTime - startTime) / 1000.0
                        echo "⏱️ [System] ${algoName} 執行結束！總耗時: ${duration} 秒"
                        
                        def runtimeFile = "${env.FRAMEWORK_PATH}/experiments/${algoName}/runtime_${params.WORKER_COUNT}w.txt"
                        sh "echo '${duration}' > ${runtimeFile}"
                        
                        // 4. 針對當前演算法進行報告檢查
                        def logFile = "${env.FRAMEWORK_PATH}/experiments/${algoName}/batch_durations_${BUILD_ID}.log"
                        if (fileExists(logFile)) {
                            def count = sh(script: "wc -l < ${logFile}", returnStdout: true).trim()
                            echo "✅ ${algoName} 實驗完成！成功處理批次數：${count}"
                        }
                    }
                }
            }
        }
        stage('生成報告與存檔') {
            steps {
                script {
                    // 5. 將存檔邏輯獨立出來，不管跑單一還是 ALL，都把有變動的 txt 和 tar.gz 存起來
                    archiveArtifacts artifacts: "experiments/**/*.tar.gz, experiments/**/runtime_*.txt", allowEmptyArchive: true
                }
            }
        }
    }
    post {
        always {
            script {
                sh "rm -f current_exp || true"
                sh "rm -f /tmp/tasks_${BUILD_ID}.txt || true"
                dir("${env.FRAMEWORK_PATH}/experiments") {
                    sh "python3 plot_comparison.py || true" 
                }
            }
        }
    }
}
