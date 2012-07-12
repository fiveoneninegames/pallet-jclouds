(ns pallet.core.jclouds-core-test
  (:use pallet.core)
  (require
   [pallet.action :as action]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute :as compute]
   [pallet.compute.jclouds :as jclouds]
   [pallet.compute.jclouds-ssh-test :as ssh-test]
   [pallet.compute.jclouds-test-utils :as jclouds-test-utils]
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.executors :as executors]
   [pallet.mock :as mock]
   [pallet.node :as node]
   [pallet.parameter :as parameter]
   [pallet.phase :as phase]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [clojure.tools.logging :as logging]
   [clojure.string :as string])
  (:use
   clojure.test
   [pallet.actions :only [exec-script]]
   [pallet.test-utils :only [bash-action clj-action test-session]]
   [pallet.action-plan :only [stop-execution-on-error]])
  (:import [org.jclouds.compute.domain NodeState OperatingSystem OsFamily]))

;; Allow running against other compute services if required
(def ^{:dynamic true} *compute-service* ["stub" "x" "x" ])

(use-fixtures
  :each
  (jclouds-test-utils/compute-service-fixture
   *compute-service*
   :extensions
   [(ssh-test/ssh-test-client ssh-test/no-op-ssh-client)]))

(use-fixtures :once (logutils/logging-threshold-fixture))

(defn seen-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [seen (atom nil)
        seen? (fn [] @seen)]
    [(clj-action [session]
       (clojure.tools.logging/info (format "Seenfn %s" name))
       (is (not @seen))
       (reset! seen true)
       [session session])
     seen?]))

(defn running-nodes [nodes]
  (filter (complement compute/terminated?) nodes))

;; ;; this test doesn't work too well if the test are run in more than
;; ;; one thread...
;; #_
;; (deftest admin-user-test
;;   (let [username "userfred"
;;         old pallet.utils/*admin-user*]
;;     (admin-user username)
;;     (is (map? pallet.utils/*admin-user*))
;;     (is (= username (:username pallet.utils/*admin-user*)))
;;     (is (= (pallet.utils/default-public-key-path)
;;            (:public-key-path pallet.utils/*admin-user*)))
;;     (is (= (pallet.utils/default-private-key-path)
;;            (:private-key-path pallet.utils/*admin-user*)))
;;     (is (nil? (:password pallet.utils/*admin-user*)))

;;     (admin-user username :password "pw" :public-key-path "pub"
;;                 :private-key-path "pri")
;;     (is (map? pallet.utils/*admin-user*))
;;     (is (= username (:username pallet.utils/*admin-user*)))
;;     (is (= "pub" (:public-key-path pallet.utils/*admin-user*)))
;;     (is (= "pri" (:private-key-path pallet.utils/*admin-user*)))
;;     (is (= "pw" (:password pallet.utils/*admin-user*)))

;;     (admin-user old)
;;     (is (= old pallet.utils/*admin-user*))))

(defn serial-environment-no-ssh
  []
  {:compute (jclouds-test-utils/compute)
   :middleware [core/translate-action-plan raise-on-error]
   :executor core/default-executor
   :algorithms
   (assoc core/default-algorithms
     :converge-fn #'pallet.core/serial-adjust-node-counts
     :lift-fn #'pallet.core/sequential-lift)})

(deftest lift-destroy-server-test
  (logging/info "lift-destroy-server-test")
  (jclouds-test-utils/purge-compute-service)
  (let [service (jclouds-test-utils/compute)
        a-node (org.jclouds.compute2/create-node (.compute service) "a")
        nodes (compute/nodes service)
        node (first nodes)
        [action seen?] (seen-fn "lift-destroy-server-test")
        servers [{:node node
                  :node-id (keyword (node/id node))
                  :phases {:destroy-server (action)}}]]
    (is (= 1 (count nodes)))
    (let [session (#'core/lift-destroy-server
                   (test-session
                    {:groups [(core/group-spec
                               :a
                               :servers servers
                               :servers-to-remove servers)]
                     :environment (serial-environment-no-ssh)}))]
      (is (seen?))
      (logging/info "lift-destroy-server-test end"))))

(deftest destroy-servers-test
  (logging/info "destroy-servers-test")
  (jclouds-test-utils/purge-compute-service)
  (let [service (jclouds-test-utils/compute)
        a-node (org.jclouds.compute2/create-node (.compute service) "a")
        nodes (compute/nodes service)
        node (first nodes)
        servers [{:node node :node-id (keyword (node/id node))}]]
    (is (= 1 (count nodes)))
    (let [session (#'core/destroy-servers
                   {:groups [(core/group-spec
                              :a
                              :servers servers
                              :servers-to-remove servers
                              :remove-group true)]
                    :environment (serial-environment-no-ssh)})]
      (is (= 0 (count (running-nodes (compute/nodes service)))))
      (is (= 1 (count (:old-nodes session))))
      (logging/info "destroy-servers-test end"))))

(deftest lift-destroy-group-test
  (logging/info "lift-destroy-group-test")
  (jclouds-test-utils/purge-compute-service)
  (let [service (jclouds-test-utils/compute)
        a-node (org.jclouds.compute2/create-node (.compute service) "a")
        nodes (compute/nodes service)
        node (first nodes)
        [action seen?] (seen-fn "lift-destroy-group-test")
        servers [{:node node
                  :node-id (keyword (node/id node))}]]
    (is (= 1 (count nodes)))
    (let [session {:groups [(core/group-spec
                             :a
                             :servers servers
                             :servers-to-remove servers
                             :phases {:destroy-group (action)}
                             :delta-count -1)]
                   :environment (serial-environment-no-ssh)}
          session (-> (test-session session)
                      core/destroy-servers
                      core/lift-destroy-group)]
      (is (seen?))
      (logging/info "lift-destroy-group-test end"))))

(deftest lift-create-group-test
  (logging/info "lift-create-group-test")
  (jclouds-test-utils/purge-compute-service)
  (let [service (jclouds-test-utils/compute)
        [action seen?] (seen-fn "lift-create-group-test")]
    (let [session {:groups [(core/group-spec
                             :a
                             :delta-count 1
                             :phases {:create-group (action)})]
                   :environment (serial-environment-no-ssh)}
          session (-> (test-session session)
                      core/lift-create-group)]
      (is (seen?))
      (logging/info "lift-create-group-test end"))))

(deftest create-servers-test
  (logging/info "create-servers-test")
  (jclouds-test-utils/purge-compute-service)
  (let [service (jclouds-test-utils/compute)]
    (is (= 0 (count (running-nodes (compute/nodes service)))))
    (let [session (#'core/create-servers
                   (test-session
                    {:groups [(core/group-spec :a :delta-count 1)]
                     :environment (serial-environment-no-ssh)}))]
      (is (= 1 (count (running-nodes (compute/nodes service)))))
      (is (= 1 (count (:new-nodes session))))
      (logging/info "create-servers-test end"))))

(deftest adjust-server-counts-test
  (logging/info "adjust-server-counts-test")
  (let [build-template org.jclouds.compute2/build-template
        service (jclouds-test-utils/compute)
        a-node (jclouds/make-node "a" :state NodeState/RUNNING)]
    (mock/expects [(org.jclouds.compute2/create-nodes
                    [compute tag n template]
                    (mock/once
                     (is (= "a" tag))
                     (is (= 1 n))
                     [a-node]))
                   (org.jclouds.compute2/build-template
                    [compute & options]
                    (mock/times 2 (apply build-template compute options)))]
                  (let [[_ session] (#'core/adjust-server-counts
                                     (test-session
                                      {:groups [(test-utils/group
                                                 :a :count 1 :delta-count 1
                                                 :servers [])]
                                       :environment
                                       {:compute service
                                        :algorithms
                                        (assoc core/default-algorithms
                                          :converge-fn
                                          #'pallet.core/serial-adjust-node-counts
                                          :lift-fn
                                          #'pallet.core/sequential-lift)}}))
                        nodes (:all-nodes session)]
                    (is (= 1 (count nodes)))
                    (is (= service (node/compute-service (first nodes))))
                    (is (= (compute/id a-node) (compute/id (first nodes)))))))
  (logging/info "converge-node-counts-test 2")
  (testing "With a good and a bad node"
    (jclouds-test-utils/purge-compute-service)
    (let [build-template org.jclouds.compute2/build-template
          service (jclouds-test-utils/compute)
          a-node (jclouds/make-node "a" :state NodeState/RUNNING)
          b-node (jclouds/make-node "a" :state NodeState/RUNNING)
          c-node (jclouds/make-node "a" :state NodeState/RUNNING)]
      (mock/expects [(org.jclouds.compute2/create-nodes
                      [compute tag n template]
                      (mock/once
                       (is (= "a" tag))
                       (is (= 3 n))
                       (throw (org.jclouds.compute.RunNodesException.
                               "a" 2
                               template
                               #{a-node}
                               {}
                               {b-node (Exception.)
                                c-node (Exception.)}))))
                     (org.jclouds.compute2/build-template
                      [compute & options]
                      (mock/times 2 (apply build-template compute options)))]
                    (let [[_ session] (#'core/adjust-server-counts
                                       (test-session
                                        {:groups [(test-utils/group
                                                   :a :count 3 :delta-count 3
                                                   :servers [])]
                                         :environment
                                         {:compute service
                                          :algorithms
                                          (assoc core/default-algorithms
                                            :converge-fn
                                            #'pallet.core/serial-adjust-node-counts
                                            :lift-fn #'pallet.core/sequential-lift)}}))
                          nodes (:all-nodes session)]
                      (is (= 1 (count (running-nodes nodes))))
                      (is (= service (node/compute-service (first nodes))))
                      (is (= (compute/id a-node)
                             (compute/id (first nodes)))))))))

(deftest parallel-converge-node-counts-test
  (let [build-template org.jclouds.compute2/build-template
        service (jclouds-test-utils/compute)
        a-node (jclouds/make-node "a" :state NodeState/RUNNING)]
    (mock/expects [(clojure.core/future-call
                    [f]
                    (mock/once (delay (f)))) ;; delay implements deref
                   (org.jclouds.compute2/create-nodes
                    [compute tag n template]
                    (mock/once
                     (is (= 1 n))
                     [a-node]))
                   (org.jclouds.compute2/build-template
                    [compute & options]
                    (mock/times 2 (apply build-template compute options)))]
                  (let [nodes (->
                               (#'core/adjust-server-counts
                                (test-session
                                 {:groups [(test-utils/group :a :delta-count 1)]
                                  :environment
                                  {:compute service
                                   :algorithms
                                   (assoc core/default-algorithms
                                     :converge-fn
                                     #'pallet.core/parallel-adjust-node-counts
                                     :lift-fn #'pallet.core/parallel-lift)}}))
                               second
                               :all-nodes)]
                    (is (= 1 (count nodes)))
                    (is (= service (node/compute-service (first nodes))))
                    (is (= (compute/id a-node) (compute/id (first nodes))))))))

(deftest nodes-in-set-test
  (let [a (group-spec "a" :image {:os-family :ubuntu})
        b (group-spec "b" :image {:os-family :ubuntu})
        a-node (jclouds/make-node "a")
        b-node (jclouds/make-node "b")]
    (is (= {a #{a-node}}
           (#'core/nodes-in-set {a a-node} nil nil)))
    (is (= {a #{a-node b-node}}
           (#'core/nodes-in-set {a #{a-node b-node}} nil nil)))
    (is (= {a #{a-node} b #{b-node}}
           (#'core/nodes-in-set {a #{a-node} b #{b-node}} nil nil))))
  (let [a (group-spec "a" :image {:os-family :ubuntu})
        b (group-spec "b" :image {:os-family :ubuntu})
        pa (group-spec "pa" :image {:os-family :ubuntu})
        pb (group-spec "pb" :image {:os-family :ubuntu})
        a-node (jclouds/make-node "a")
        b-node (jclouds/make-node "b")]
    (is (= {pa #{a-node}}
           (#'core/nodes-in-set {a a-node} "p" nil)))
    (is (= {pa #{a-node b-node}}
           (#'core/nodes-in-set {a #{a-node b-node}} "p" nil)))
    (is (= {pa #{a-node} pb #{b-node}}
           (#'core/nodes-in-set {a #{a-node} b #{b-node}} "p" nil)))
    (is (= {pa #{a-node} pb #{b-node}}
           (#'core/nodes-in-set {a a-node b b-node} "p" nil)))))

(deftest node-in-types?-test
  (let [a (group-spec "a")
        b (group-spec "b")]
    (is (#'core/node-in-types? [a b] (jclouds/make-node "a")))
    (is (not (#'core/node-in-types? [a b] (jclouds/make-node "c"))))))

(def test-component
  (bash-action [session arg] [(str arg) session]))

(deftest lift-test
  (testing "jclouds"
    (let [local (group-spec "local" :image {:os-family :ubuntu})
          [localf seen?] (seen-fn "lift-test")]
      (is (.contains
           "bin"
           (with-out-str
             (lift {local (jclouds/make-localhost-node)}
                   :phase [(phase/phase-fn (exec-script (ls "/")))
                           (phase/phase-fn (localf))]
                   :user (assoc utils/*admin-user*
                           :username (test-utils/test-username)
                           :no-sudo true)
                   :compute nil))))
      (is (seen?)))))

(deftest lift2-test
  (let [[localf seen?] (seen-fn "lift2-test")
        [localfy seeny?] (seen-fn "lift2-test y")
        x1 (group-spec :x1 :phases {:configure (localf)})
        y1 (group-spec :y1 :phases {:configure (localfy)})]
    (is (map?
         (lift {x1 (jclouds/make-unmanaged-node "x" "localhost")
                y1 (jclouds/make-unmanaged-node "y" "localhost")}
               :user (assoc utils/*admin-user*
                       :username (test-utils/test-username)
                       :no-sudo true)
               :compute nil)))
    (is (seen?))
    (is (seeny?))))

(deftest lift*-nodes-binding-test
  (let [a (group-spec "a")
        b (group-spec "b")
        na (jclouds/make-node "a")
        nb (jclouds/make-node "b")
        nc (jclouds/make-node "c" :state NodeState/TERMINATED)]
    (mock/expects [(sequential-apply-phase
                    [session]
                    (do
                      (is (= #{na nb} (set (:all-nodes session))))
                      (is (= #{na nb} (set
                                       (map
                                        :node (-> session :group :servers)))))
                      (is (= #{na nb}
                             (set (map
                                   :node
                                   (-> session :groups first :servers)))))
                      [[] session]))]
                  (lift*
                   (test-session
                    {:node-set {a #{na nb nc}}
                     :phase-list [:configure]
                     :environment
                     {:compute nil
                      :user utils/*admin-user*
                      :middleware *middleware*
                      :algorithms
                      {:converge-fn #'pallet.core/serial-adjust-node-counts
                       :lift-fn sequential-lift}}})))
    (mock/expects [(sequential-apply-phase
                    [session]
                    (do
                      (is (= #{na nb} (set (:all-nodes session))))
                      (is (= na
                             (-> session
                                 :groups first :servers first :node)))
                      (is (= nb
                             (-> session
                                 :groups second :servers first :node)))
                      [[] session]))]
                  (lift*
                   (test-session
                    {:node-set {a #{na} b #{nb}}
                     :phase-list [:configure]
                     :environment
                     {:compute nil
                      :user utils/*admin-user*
                      :middleware *middleware*
                      :algorithms
                      {:converge-fn #'pallet.core/serial-adjust-node-counts
                       :lift-fn sequential-lift}}})))))

;; need to mock protocol function :
;; (deftest lift-multiple-test
;;   (let [a (group-spec "a")
;;         b (group-spec "b")
;;         na (jclouds/make-node "a")
;;         nb (jclouds/make-node "b")
;;         nc (jclouds/make-node "c")]
;;     (mock/expects [(pallet.compute/nodes
;;                      [_]
;;                      (mock/once [na nb nc]))
;;                    (sequential-apply-phase
;;                     [session group-nodes]
;;                     (mock/times 12 ; 2 phases, 2 groups :pre, :after, :configure
;;                       (is (= #{na nb nc} (set (:all-nodes session))))
;;                       (let [m (into
;;                                {}
;;                                (map (juxt :group-name identity)
;;                                     (:groups session)))]
;;                         (is (= na (-> m :a :servers first :node)))
;;                         (is (= nb (-> m :b :servers first :node)))
;;                         (is (= 2 (count (:groups session)))))
;;                       []))]
;;                   (lift [a b] :compute (jclouds-test-utils/compute)
;;                         :environment
;;                         {:algorithms
;;                          {:lift-fn pallet.core/sequential-lift}}))))

(deftest create-nodes-test
  (let [a (jclouds/make-node "a")
        session (#'core/create-nodes
                 {:compute (jclouds-test-utils/compute)
                  :group (group-spec :a :servers [{:node a}] :delta-count 1)
                  :target-id :a
                  :environment {:algorithms core/default-algorithms}})]
    (is (seq (:new-nodes session)))
    (is (= 1 (count (:new-nodes session))))))

(deftest destroy-nodes-test
  (testing "remove all"
    (let [a (jclouds/make-node "a")
          session (#'core/remove-nodes
                   {:compute (jclouds-test-utils/compute)
                    :group (core/group-spec
                            :a :servers-to-remove [{:node a}])})]
      (is (= [a] (:old-nodes session)))))
  (testing "remove some"
    (let [a (jclouds/make-node "a")
          b (jclouds/make-node "a")
          session (#'core/remove-nodes
                   {:compute (jclouds-test-utils/compute)
                    :group (core/group-spec
                            :a
                            :servers [{:node a} {:node b}]
                            :servers-to-remove [{:node a}])})]
      (is (seq (:old-nodes session)))
      (is (= 1 (count (:old-nodes session))))
      (is (= "a" (node/group-name (first (:old-nodes session))))))))

(deftest converge*-test
  (logging/info "converge*-test")
  (let [a (group-spec "a")
        b (group-spec "b")
        na (jclouds/make-node "a")
        nb (jclouds/make-node "b")
        nb2 (jclouds/make-node "b" :id "b2" :state NodeState/TERMINATED)]
    (mock/expects [(sequential-apply-phase
                    [session]
                    (do
                      (is (=
                           #{"a" "b"}
                           (set (map compute/group-name (:all-nodes session)))))
                      [[] session]))
                   (org.jclouds.compute2/nodes-with-details [_] [na nb nb2])]
                  (converge*
                   (test-session
                    {:node-set [(assoc a :count 1) (assoc b :count 1)]
                     :phase-list [:configure]
                     :environment
                     {:compute (jclouds-test-utils/compute)
                      :middleware *middleware*
                      :algorithms
                      {:converge-fn #'pallet.core/serial-adjust-node-counts
                       :lift-fn sequential-lift}}}))))
  (logging/info "converge*-test end"))

(deftest converge-with-environment-test
  (let [a (group-spec :a)]
    (mock/expects [(pallet.core/create-nodes
                    [session]
                    (do
                      (let [group (:group session)]
                        (is (= 1 (:delta-count group)))
                        (is (= (:group-name group) :a))
                        (is (= (-> group :image :os-family) :centos)))
                      (update-in session [:new-nodes]
                                 conj (jclouds/make-node "a"))))]
                  (converge*
                   (test-session
                    {:node-set [(assoc a :count 1)]
                     :phase-list [:configure]
                     :environment
                     {:compute (jclouds-test-utils/compute)
                      :middleware *middleware*
                      :algorithms
                      {:converge-fn #'pallet.core/serial-adjust-node-counts
                       :lift-fn sequential-lift
                       :execute-status-fn stop-execution-on-error}
                      :groups {:a {:image {:os-family :centos}}}
                      :user (utils/make-user "fred")}})))))

(deftest converge-test
  (jclouds-test-utils/purge-compute-service)
  (let [hi (bash-action [session] ["Hi" session])
        id "c-t"
        node (group-spec "c-t" :phases {:configure (hi)})
        session (converge {node 2}
                          :compute (jclouds-test-utils/compute)
                          :environment {:executor executors/echo-executor})]
    (is (map? session))
    (is (map? (-> session :results)))
    (is (map? (-> session :results first second)))
    (is (:configure (-> session :results first second)))
    (is (some
         #(= "Hi" %)
         (:configure (-> session :results first second))))
    (is (= 2 (count (:all-nodes session))))
    (is (= 2
           (count (running-nodes
                   (compute/nodes (jclouds-test-utils/compute))))))
    (testing "remove some instances"
      (let [session (converge {node 1}
                              :compute (jclouds-test-utils/compute)
                              :environment {:executor executors/echo-executor})]
        (is (= 1 (count (running-nodes (:all-nodes session)))))
        (is (= 1 (count (running-nodes
                         (compute/nodes
                          (jclouds-test-utils/compute))))))
        (is (some
             #(= "Hi" %)
             (:configure (-> session :results first second))))))
    (testing "no instance count change with new-node-selector"
      (let [session (converge {node 1}
                              :compute (jclouds-test-utils/compute)
                              :node-set-selector #'core/new-node-set-selector
                              :environment {:executor executors/echo-executor})]
        (is (= 1 (count (running-nodes (:all-nodes session)))))
        (is (= 1 (count (running-nodes
                         (compute/nodes
                          (jclouds-test-utils/compute))))))
        (is (not (some
                  #(= "Hi" %)
                  (:configure (-> session :results first second)))))))
    (testing ":settings and :configure phases are enforced"
      (let [session (converge {node 1}
                              :phase [:non-configure]
                              :compute (jclouds-test-utils/compute)
                              :node-set-selector #'core/new-node-set-selector
                              :environment {:executor executors/echo-executor})]
        (is (= [:settings :configure :non-configure] (:phase-list session)))))
    (testing "remove all instances"
      (let [session (converge {node 0}
                              :compute (jclouds-test-utils/compute)
                              :environment {:executor executors/echo-executor})]
        (is (= 0 (count (running-nodes (:all-nodes session)))))))))


(deftest converge-with-failed-nodes-test
  (testing "With only bad nodes"
    (jclouds-test-utils/purge-compute-service)
    (let [build-template org.jclouds.compute2/build-template
          service (jclouds-test-utils/compute)]
      (is (zero?
           (count (filter compute/running? (compute/nodes service)))))
      (mock/expects [(org.jclouds.compute2/create-nodes
                      [compute tag n template]
                      (mock/once
                       (is (= "a" tag))
                       (is (= 2 n))
                       (throw (org.jclouds.compute.RunNodesException.
                               "a" 2
                               template
                               #{}
                               {}
                               (hash-map
                                (jclouds/make-node
                                 "a" :state NodeState/RUNNING)
                                (Exception.)
                                (jclouds/make-node
                                 "a" :id "a1" :state NodeState/RUNNING)
                                (Exception.))))))
                     (org.jclouds.compute2/build-template
                      [compute & options]
                      (mock/times 2 (apply build-template compute options)))]
                    (is (thrown?
                         RuntimeException
                         #"No additional nodes could be started"
                         (core/converge
                          {(core/group-spec :a) 2}
                          :compute service
                          :environment
                          {
                           :algorithms
                           {:converge-fn
                            #'pallet.core/serial-adjust-node-counts}})))
                    (is (zero?
                         (count
                          (filter
                           compute/running? (compute/nodes service)))))))))

(deftest lift-with-runtime-params-test
  ;; test that parameters set at execution time are propogated
  ;; between phases
  (let [assoc-runtime-param (clj-action
                             [session]
                             [session
                              (parameter/assoc-for-target session [:x] "x")])

        get-runtime-param (bash-action
                           [session]
                           [(format
                            "echo %s" (parameter/get-for-target session [:x]))
                            session])
        node (group-spec
              "localhost"
              :phases
              {:configure (assoc-runtime-param)
               :configure2 (fn [session]
                             (is (= (parameter/get-for-target session [:x])
                                    "x"))
                             ((get-runtime-param) session))})
        session (lift {node (jclouds/make-localhost-node)}
                      :phase [:configure :configure2]
                      :user (assoc utils/*admin-user*
                              :username (test-utils/test-username)
                              :no-sudo true)
                      :compute (jclouds-test-utils/compute))]
    (is (map? session))
    (is (map? (-> session :results)))
    (is (map? (-> session :results first second)))
    (is (-> session :results :localhost :configure))
    (is (-> session :results :localhost :configure2))
    (let [{:keys [out err exit]} (-> session
                                     :results :localhost :configure2 first)]
      (is out)
      (is (string/blank? err))
      (is (zero? exit)))))

(deftest cluster-test
  (jclouds-test-utils/purge-compute-service)
  (let [cluster (cluster-spec
                 "c"
                 :groups [(group-spec
                           "g1" :count 1 :image {:os-family :ubuntu})
                          (group-spec
                           "g2" :count 2 :image {:os-family :ubuntu})])]
    (testing "converge-cluster"
      (let [session
            (converge cluster :compute (jclouds-test-utils/compute))]
        (is (= 3 (count (:new-nodes session))))
        (is (= 3 (count (:all-nodes session))))
        (is (= 3 (count (:selected-nodes session))))
        (is (empty? (:old-nodes session))))
      (is (= 3 (count
                (running-nodes (compute/nodes (jclouds-test-utils/compute)))))))
    (testing "lift-cluster"
      (let [session
            (lift cluster :compute (jclouds-test-utils/compute))]
        (is (empty? (:new-nodes session)))
        (is (= 3 (count (:all-nodes session))))
        (is (= 3 (count (:selected-nodes session))))
        (is (empty? (:old-nodes session))))
      (is (= 3 (count
                (running-nodes (compute/nodes (jclouds-test-utils/compute)))))))
    (testing "destroy-cluster"
      (let [session
            (converge {cluster 0} :compute (jclouds-test-utils/compute))]
        (is (empty? (:all-nodes session)))
        (is (empty? (:new-nodes session)))
        (is (empty? (:selected-nodes session)))
        (is (= 3 (count (:old-nodes session)))))
      (is (= 0
             (count
              (running-nodes (compute/nodes (jclouds-test-utils/compute)))))))))
