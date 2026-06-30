pipeline {
    agent any
    parameters {
        // <<< 改:原本單一 ALGORITHM + 單一 WORKER_COUNT,改成分階段控制
        string(name: 'PARALLEL_WORKERS', defaultValue: '1,2,3,4,5', description: '階段一(序列):round-robin/random 要跑的worker數清單,逗號分隔。留空則跳過階段一')
        string(name: 'SERIAL_WORKERS',   defaultValue: '1,2,3,4,5', description: '階段二(序列):lpt/spt 要跑的worker數清單,逗號分隔。留空則跳過階段二')
        string(name: 'CPU_MODE',          defaultValue: 'fixed', description: 'fixed=固定核心(用CPU_FIXED_CORES) | variable=每worker不同(用CPU_VARIABLE_CORES)')
        string(name: 'CPU_FIXED_CORES',   defaultValue: '2', description: 'CPU_MODE=fixed 時,每個worker的核心數')
        string(name: 'CPU_VARIABLE_CORES',defaultValue: '7,5,3,2,1', description: 'CPU_MODE=variable 時,依worker編號分配的核心數(由大到小;8核VM建議最大設7留餘裕)')
        string(name: 'TASK_FILE', defaultValue: '/var/lib/jenkins/batch_tasks_bug1_real_10x.txt', description: '任務列表檔案路徑')
        booleanParam(name: 'CLEAN_REPORTS', defaultValue: true, description: '執行前清理舊報告(保證EMA只吃本次數據,建議維持勾選)')
        // <<< 新增:基準驗證的覆蓋率門檻(%)。健康跑預期=100;留 5% 容忍極少數耗時<1ms 被濾掉的 task。要嚴格全覆蓋設 100
        string(name: 'MIN_EMA_COVERAGE_PCT', defaultValue: '95', description: '階段一·五基準驗證:每支基準的 task 覆蓋率門檻(%)。低於此值即中斷,不進階段二')
    }
    options {
        timeout(time: 24, unit: 'HOURS')
        timestamps()
    }
    stages {
        stage('清理') {
            steps {
                script {
                    env.FRAMEWORK_PATH = env.WORKSPACE
                    // <<< 改:清理移到最前面只做一次,且清掉全部四個演算法的舊log(保證EMA乾淨)
                    def allAlgos = ['round-robin', 'random-dynamic', 'lpt-dynamic', 'spt-dynamic']
                    allAlgos.each { a -> sh "mkdir -p ${env.FRAMEWORK_PATH}/experiments/${a}" }
                    if (params.CLEAN_REPORTS) {
                        allAlgos.each { a ->
                            sh "rm -rf ${env.FRAMEWORK_PATH}/experiments/${a}/* || true"
                        }
                        echo "🧹 已清空 experiments/ 舊 log,EMA 將只讀本次數據"
                    }
                }
            }
        }

        stage('階段一:序列 round-robin + random') {
            // <<< 改:從「10條分支(5個worker數×2個演算法)全部塞進同一個parallel區塊同時發動」
            //     改成「依worker數逐輪序列執行,跟階段二(lpt/spt)同一套架構」。
            //     決策原因:原平行設計同時pod需求 = (Σwc)×2演算法 = (1+2+3+4+5)×2 = 30個worker pod,
            //     但叢集只有5台VM。不管anti-affinity的label範圍怎麼設(全域/scoped),只要同時發動的
            //     pod數>VM數,必然出現「全域→大量Pending排隊」或「scoped→跨分支共用VM搶CPU、計時噪音
            //     視硬體規格與機率而定」兩者之一。若要結構上保證「一台VM永遠只跑一個worker pod、零例外、
            //     不用驗證、不用猜機率」,唯一辦法是讓同時存在的worker pod數本身≤5,即序列化每個分支。
            //     改動後:同一時刻只有一個(演算法,worker數)分支在執行,該分支內部的worker才彼此parallel
            //     (最多5個,剛好=VM數,排得下不卡)。
            //     代價:階段一耗時從「趨近最久那條分支」變成「十條分支耗時總和」,保守估計拉長數倍,
            //     換回「不需要再討論共擠機率、不需要量nodeName驗證」的結構性保證。
            //     round-robin.groovy / random-dynamic.groovy 本身不用改:被序列或平行呼叫,程式邏輯
            //     完全一樣;其 scoped anti-affinity 在序列情境下與全域等價(同時只有一個分支存在)。
            when { expression { params.PARALLEL_WORKERS?.trim() } }
            steps {
                script {
                    def cpuVariableList = params.CPU_VARIABLE_CORES.split(',').collect { it.trim() }
                    def parallelAlgos = ['round-robin', 'random-dynamic']   // 不動:演算法清單不變
                    def wcs = params.PARALLEL_WORKERS.split(',').collect { it.trim().toInteger() }   // 不動:參數名稱沿用,僅內部執行方式改為序列

                    // <<< 改:原本用 wcs.max() 在Jenkins script-security沙箱裡會被擋
                    //     (DefaultGroovyMethods.max(Collection) 不在預設白名單,需管理員手動核准
                    //     簽名才能用)。改成用 .each 手動找最大值,只依賴已驗證可用的方法(.each 在
                    //     「清理」階段已成功執行過),不用動 Jenkins 後台設定就能直接跑。
                    def maxWc = 0
                    wcs.each { if (it > maxWc) maxWc = it }
                    echo "🚀 階段一序列執行開始:${wcs.size()} 個worker數 × ${parallelAlgos.size()} 個基準演算法," +
                         "共 ${wcs.size() * parallelAlgos.size()} 個分支,依序執行(每分支內部worker仍平行,最多${maxWc}個,不超過VM數)"

                    wcs.each { wc ->
                        parallelAlgos.each { algoName ->
                            def groupTag = "${algoName}-${wc}w"
                            def cpuCores = (params.CPU_MODE == 'fixed') ? params.CPU_FIXED_CORES.trim() : null
                            def cpuPerWorker = (params.CPU_MODE == 'variable') ? cpuVariableList.take(wc) : null
                            echo "🚀 [序列] ${algoName} (${wc}w, CPU_MODE=${params.CPU_MODE})"   // <<< 改:[並行]→[序列]反映實際執行方式
                            def algorithm = load "${env.FRAMEWORK_PATH}/algorithms/${algoName}.groovy"
                            long st = System.currentTimeMillis()
                            algorithm.execute(
                                taskFile: params.TASK_FILE,
                                workerCount: wc,
                                groupTag: groupTag,
                                cpuCores: cpuCores,
                                cpuPerWorker: cpuPerWorker
                            )
                            long et = System.currentTimeMillis()
                            def dur = (et - st) / 1000.0
                            echo "⏱️ [序列] ${algoName} (${wc}w) 完成,耗時 ${dur}s"
                            sh "echo '${dur}' > ${env.FRAMEWORK_PATH}/experiments/${algoName}/runtime_${wc}w.txt"

                            // <<< 新增:等待本分支(groupTag)的worker pod真正清空,才進下一條分支。
                            //     決策原因:execute()返回只代表Groovy/stash步驟跑完,不代表底層Pod已被
                            //     kubelet完全終止並釋放CPU/memory request——Pod終止是異步的。若緊接著
                            //     下一條分支立刻建立新pod,集群可能還卡著上一條分支Terminating中的pod,
                            //     CPU request還沒釋放,新pod會Pending(實測build 296的lpt-5w即出現此狀況:
                            //     "5 Insufficient cpu...No preemption victims found")。
                            //     放置位置刻意放在dur計算與寫檔之後:確保這段等待時間絕對不會混進這次
                            //     記錄的演算法耗時dur裡,避免汙染實驗數據;等待秒數只影響「下一條分支幾秒
                            //     後才開始」,跟這條分支本身的測量結果無關。
                            //     用 thesis-exp-group=${groupTag} 標籤精準篩選本分支的pod(其餘分支不受影響);
                            //     最多等60秒(30次×2秒),歸零就立即跳出,不會無故拖長太久。
                            sh """
                                for i in \$(seq 1 30); do
                                    remaining=\$(kubectl get pods -n default -l thesis-exp-group=${groupTag} --no-headers 2>/dev/null | wc -l)
                                    if [ "\$remaining" -eq 0 ]; then
                                        echo "✅ ${groupTag} 的所有pod已清空"
                                        break
                                    fi
                                    echo "⏳ 等待 ${groupTag} 殘留pod清空,目前還有 \$remaining 個 (第\${i}次檢查)"
                                    sleep 2
                                done
                            """
                        }
                    }
                }
            }
        }

        // <<< 新增階段:在啟動任何 lpt/spt pod「之前」,一次驗完階段二要用到的所有 worker 數,其
        //     round-robin / random-dynamic 兩支基準 log 是否「存在且覆蓋率達標」。任一不過立即 error,
        //     於是不會白跑階段二(fail-fast)。驗證邏輯見 algorithms/validate-baselines.groovy。
        //     位置:必須在階段一(產生基準 log)之後、階段二(消費基準 log)之前。
        stage('階段一·五:基準驗證(fail-fast,避免階段二白跑)') {
            when { expression { params.SERIAL_WORKERS?.trim() } }
            steps {
                script {
                    def wcs = params.SERIAL_WORKERS.split(',').collect { it.trim().toInteger() }
                    def validator = load "${env.FRAMEWORK_PATH}/algorithms/validate-baselines.groovy"
                    validator.validate(
                        taskFile: params.TASK_FILE,
                        workerCounts: wcs,
                        minCoveragePct: params.MIN_EMA_COVERAGE_PCT.trim().toInteger()
                    )
                }
            }
        }

        stage('階段二:序列 lpt + spt') {
            when { expression { params.SERIAL_WORKERS?.trim() } }
            steps {
                script {
                    def cpuVariableList = params.CPU_VARIABLE_CORES.split(',').collect { it.trim() }
                    def serialAlgos = ['lpt-dynamic', 'spt-dynamic']   // 順序固定:lpt 先 spt 後
                    def wcs = params.SERIAL_WORKERS.split(',').collect { it.trim().toInteger() }

                    wcs.each { wc ->
                        serialAlgos.each { algoName ->
                            echo "🚀 [序列] ${algoName} (${wc}w)"
                            def cpuCores = (params.CPU_MODE == 'fixed') ? params.CPU_FIXED_CORES.trim() : null
                            def cpuPerWorker = (params.CPU_MODE == 'variable') ? cpuVariableList.take(wc) : null
                            def groupTag = "${algoName}-${wc}w"   // <<< 新增:抽出groupTag變數,等待邏輯需要用同一個值
                            def algorithm = load "${env.FRAMEWORK_PATH}/algorithms/${algoName}.groovy"
                            long st = System.currentTimeMillis()
                            algorithm.execute(
                                taskFile: params.TASK_FILE,
                                workerCount: wc,
                                groupTag: groupTag,
                                cpuCores: cpuCores,
                                cpuPerWorker: cpuPerWorker
                            )
                            long et = System.currentTimeMillis()
                            def dur = (et - st) / 1000.0
                            echo "⏱️ [序列] ${algoName} (${wc}w) 完成,耗時 ${dur}s"
                            sh "echo '${dur}' > ${env.FRAMEWORK_PATH}/experiments/${algoName}/runtime_${wc}w.txt"

                            // <<< 新增:等待邏輯,原因與放置位置同階段一(見上方註解),避免下一條分支
                            //     (lpt→spt,或下一個worker數)因上一條的pod還沒終止完畢而Pending。
                            //     這正是build 296實際出現"lpt-296-*...Insufficient cpu"的成因。
                            sh """
                                for i in \$(seq 1 30); do
                                    remaining=\$(kubectl get pods -n default -l thesis-exp-group=${groupTag} --no-headers 2>/dev/null | wc -l)
                                    if [ "\$remaining" -eq 0 ]; then
                                        echo "✅ ${groupTag} 的所有pod已清空"
                                        break
                                    fi
                                    echo "⏳ 等待 ${groupTag} 殘留pod清空,目前還有 \$remaining 個 (第\${i}次檢查)"
                                    sleep 2
                                done
                            """
                        }
                    }
                }
            }
        }

        stage('存檔') {
            steps {
                script {
                    archiveArtifacts artifacts: "experiments/**/runtime_*.txt, experiments/**/batch_durations_*.log", allowEmptyArchive: true
                }
            }
        }
    }
    post {
        always {
            script {
                sh "rm -f current_exp || true"
                dir("${env.FRAMEWORK_PATH}/experiments") {
                    sh "python3 plot_comparison.py || true"
                }
            }
        }
    }
}
