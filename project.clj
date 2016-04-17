(defproject chantrix "0.1.1"
  :description
  "async extensions, composable middleware, and
  feedback control for core.async"

  :url
  "http://github.com/opentable/chantrix"

  :license
  {:name "Eclipse Public License"
   :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies
  [[org.clojure/clojure "1.8.0"]
   [org.clojure/core.async "0.2.374"]
   [org.clojure/tools.trace "0.7.9" :scope "provided"]
   [cheshire "5.5.0" :scope "provided"]
   [http-kit "2.1.19" :scope "provided"]]

  :profiles
  {:dev     {:source-paths ["dev"]}
   :uberjar {:aot :all}}

  :test-selectors
  {:default     (constantly true)
   :unit        :unit
   :integration :integration
   :performance :performance
   :all         (constantly true)}

  :eastwood
  {:namespaces [:source-paths]})
