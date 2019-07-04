(ns frontend.ui
  (:require [goog.string :as gstring]
            [goog.string.format]
            [reagent.core :as r]
            [reagent.dom :refer [dom-node]]
            [ajax.core :refer [GET]]
            [clojure.string :refer [lower-case]]
            [cljss.reagent :refer-macros [defstyled]]
            [frontend.styles :as s :refer [compose]]
            [frontend.util     :as util :refer [by-id]]
            [frontend.requests :as req]
            [frontend.player :as player]
            [frontend.data     :as data]))

(defn set-active! [k]
  (reset! data/active k))

(defonce navbar-height 48)
(defonce medium-bar-divisor 4)
(defonce small-bar-divisor 24)
(defonce total-navbar-height (+ navbar-height
                                (/ navbar-height medium-bar-divisor)
                                (/ navbar-height small-bar-divisor)))
(defonce sidebar-width 180)

(defn prevent-play-pause [e]
  (when (.-stopPropagation e)
    (.stopPropagation e)))

(defstyled padded-div :div
  {:margin-top (str total-navbar-height "px")
   :margin-bottom (str (when @data/player-html
                         (.-offsetHeight @data/player-html)) "px")
   :padding "8px"
   :height "100%"})

(defn back-toggle [set-active title]
  [:button {:class (compose (s/navbar-toggle navbar-height) "la la-arrow-left")
            :title title
            :on-click #(set-active! set-active)}])

(defn setting [props & children]
  (reset! data/sidebar-toggle-function {:function 'back :dest :settings :title "Settings"})
  (fn [props & children]
    (into [padded-div (r/merge-props {} props)] children)))

(defn full-screen-backdrop
  "Provides a full screen exit button for whatever context"
  [state z-index]
  (fn [state z-index]
    [:div {:class (compose
                   (let [[w h] @data/viewport-dims] (s/full-screen-backdrop w h z-index))
                   (if @state (s/full-screen-backdrop-active)))}]))

(defn random []
  ()
  (let [get-random-songs (fn [])]
    (fn []
      [padded-div "Random"])))

(defn songs []
  (let [orderby "title"]
    (req/get-all "song" data/songs orderby))
  (let [filter-field (r/atom "")]
    (fn []
      [padded-div {:id "songs"}
       [:div {:class (compose (s/songs-filter total-navbar-height))}
        [:div {:class (compose (s/songs-filter-text))} "Filter"]
        [:input {:type "text"
                 :on-key-press prevent-play-pause
                 :class (compose (s/songs-filter-input))
                 :on-change (fn [e] (reset! filter-field (-> e .-target .-value)))}]]
       [:div {:class (compose (s/songs-area))}
        (doall
        (for [song (let [filter-re-fn (fn [field m] (re-matches (re-pattern (str ".*" @filter-field ".*")) (clojure.string/lower-case (m field))))]
                     (filter #(or ((partial filter-re-fn :title) %) ((partial filter-re-fn :artist) %)) @data/songs))]
         [:div {:key (song :id)
                :class (s/song)
                :on-click (fn [] (player/play-song! (song :id)))}
          [:div {:class (s/song-element)} (song :title)]
          [:div {:class (compose (s/font-size 12) (s/song-element))} (song :artist)]
          [:hr {:style {:margin "0"}}]]))]])))

(defn artists []
  (req/get-all "artist" data/artists "name")
  (fn []
    [padded-div {:id "artists"}
     (for [artist @data/artists]
       [:div {:key (str "artist-" (artist :id))} (artist :name)])]))

(defn album-button []
  (let [this (r/current-component)
        clicked? (r/atom false)]
    (fn []
      [:i (r/merge-props {:class (compose (s/circle-bounding) (s/album-button 4 4) (if @clicked? (s/album-button-clicked)))
                          :on-click (fn [] (reset! clicked? (not @clicked?)))}
                         (r/props this))])))

(defn album []
  (let [album-info  (r/atom {})
        artist-info (r/atom {})
        mouse-on?    (r/atom false)
        this (r/current-component)
        width-height 168]
    (GET (str (req/req-str "album") "/" ((r/props this) :albumid))
         {:handler (req/album-handler album-info artist-info)})
    (fn []
      [:div (r/merge-props {:class (s/album width-height 2)
                            :on-mouse-enter (fn [] (reset! mouse-on? true))
                            :on-mouse-leave (fn [] (reset! mouse-on? false))
                            :on-click (fn [] (println "album" ((r/props this) :albumid) "clicked"))}
                           (r/props this))
       [:div {:class (s/album-inside)}
        [:div {:class (s/album-background)}]
        [:i {:class (compose "la la-music" (s/album-img))}]
        [:div {:class (compose (s/no-select) (s/album-info))}
         [:b (let [a (@artist-info :name)] (if a a "Unknown Artist"))]
         [:br]
         (@album-info :title)]
        [:div {:class (compose (s/album-buttons) (if @mouse-on? (s/album-buttons-show)))}
         [album-button {:class (compose "la la-bookmark")}]
         [album-button {:class (compose "la la-play")}]]]])))

(defn albums []
  ()
  (req/get-all "album" data/albums "title")
  (fn []
    [padded-div {:id "albums"}
     (for [a @data/albums]
       [album {:key (str "album-" (a :id))
               :albumid (a :id)}])]))

(defn toggle-visibility! [state]
  (swap! state not))

(defn sidebar-toggle []
  (fn []
    [:button {:class (compose (s/navbar-toggle navbar-height))
              :on-click #(toggle-visibility! data/sidebar-open)}
     [full-screen-backdrop data/sidebar-open "-1"]
     [:span {:class (s/sr-only)} "Toggle Navigation"]
     [:i {:class (compose (if @data/sidebar-open (s/color-on-active s/secondary))
                          (s/toggle) (s/circle-bounding) "la la-bars")}]]))

(defn sidebar-li-click-event! [keyw]
  (fn [e]
    (reset! data/sidebar-open false)
    (set-active! keyw)))

(defn sidebar []
  (fn []
    [:div {:class (compose
                   ;; subtracting 1 from the innerheight prevents
                   ;; the sidebar from exceeding the viewport size
                   (let [[_ height] @data/viewport-dims]
                     (s/sidebar (- height 1) total-navbar-height sidebar-width))
                   (if @data/sidebar-open (s/sidebar-open)))}
     [:ul {:class (s/sidebar-ul)}
      (doall (for [item data/categories]
               (let [keyw (item :set-active)]
                 (if (= (item :name) 'divider)
                   [:hr {:key (item :name)
                         :class (s/hr)}]
                   [:li {:key (item :name)
                         :class (compose
                                 (s/menu-li)
                                 (if (= @data/active keyw) (s/highlighted-row)))
                         :on-click (sidebar-li-click-event! keyw)}
                    [:a {:class (compose
                                 (item :class)
                                 (s/sidebar-li-icon))}]
                    [:a {:class (compose (s/sidebar-li-a)
                                         (s/pad-in-start 8)
                                         (s/right))
                         :style {:padding-right 5}} (item :name)]]))))]]))

(defn manage-library-elem [props libs lib-idx]
  (let [lib         (@libs lib-idx)
        editing     (r/atom (true? (lib :editing)))
        id          (lib :id)
        set-editing (fn [v] (reset! editing v))
        lib-a       (r/atom {:id   (lib :id)
                             :name (lib :name)
                             :path (lib :path)})
        update-input (fn [target e]
                       (swap! lib-a assoc
                              target  (-> e .-target .-value)
                              :edited true))]
    (fn [props libs lib-idx]
      (if @editing
        [:div (r/merge-props {:class (compose (s/manage-library-row 3))
                              :on-key-press (fn [e] (if (-> e .-preventDefault) (.preventDefault e)))
                              :on-click (fn [e](if (.-stopPropagation e) (.stopPropagation e)))} props)
         [:input {:type :text
                  :name :name
                  :on-key-press prevent-play-pause
                  :value (@lib-a :name)
                  :class (compose (s/manage-lib-cell))
                  :on-change (partial update-input :name)}]
         [:input {:value (@lib-a :path)
                  :on-key-press prevent-play-pause
                  :class (compose (s/manage-lib-cell))
                  :on-change (partial update-input :path)}]
         [:button {:class (compose (s/button) (s/bg s/green s/border-green) "la la-check")
                   :title "Confirm"
                   :on-click (fn []
                               (let [{id :id name :name path :path} @lib-a]
                                 (when (@lib-a :edited)
                                   (if (lib :new)
                                     (req/create-library name path)
                                     (req/update-library id name path)))
                                 (let [update-lib (fn [target value] (swap! libs assoc-in [lib-idx target] value))]
                                   (update-lib :name name)
                                   (update-lib :path path)))
                               (set-editing false))}]
         
         (assoc-in [{:name "lib1"} {:name "lib2"}] [0 :name] "lib3")
         
         [:button {:class (compose (s/button) (s/bg s/yellow s/border-yellow) "la la-close")
                   :title "Cancel"
                   :on-click (fn []
                               (swap! lib-a dissoc :edited)
                               (if (lib :new)
                                 (swap! data/libraries #(vec (filter (fn [m] (not (m :new))) %)))
                                 (do (reset! lib-a lib) (set-editing false))))}]
         [:button {:class (compose (s/button) (s/bg s/red s/border-red) "la la-trash-o")
                   :title "Delete library"}]]
        [:div (r/merge-props {:class (compose (s/manage-library-row 2))
                              :on-click (fn [e] (if (.-stopPropagation e) (.stopPropagation e)))} props)
         [:div {:class (compose (s/manage-lib-cell))} (@lib-a :name)]
         [:div {:class (compose (s/manage-lib-cell))} (@lib-a :path)]
         [:button {:class (compose (s/button) "la la-rotate-left")
                   :title "Re-scan library"
                   :on-click #(req/scan-library id)}]
         [:button {:class (compose (s/button) "la la-edit")
                   :title "Edit library"
                   :on-click (fn [] (set-editing true))}]]))))

(defn manage-library-menu [state]
  (req/get-all "library" data/libraries "id")
  (fn [state]
    (let [[w h] @data/viewport-dims
          z-index 50]
      [setting {:on-click (fn [e] (if (.-stopPropagation e) (.stopPropagation e)))}
       [:h2 "Manage Libraries"]
       [:div {:class (compose (s/manage-library-menu w h z-index))
              :on-click (fn [e] (if (.-stopPropagation e) (.stopPropagation e)))}
        [:div {:class (compose (s/manage-library-row 2))}
         [:div {:class (compose (s/manage-lib-cell) (s/grid-column "1"))} "Name"]
         [:div {:class (compose (s/manage-lib-cell) (s/grid-column "2"))} "Path"]]
        (doall
         (for [lib-idx (range (count @data/libraries))]
           [manage-library-elem {:key lib-idx} data/libraries lib-idx]))
        [:div {:class (compose (s/manage-library-row 1))}
         [:button {:class (compose (s/button) (s/grid-column "3") "la la-plus")
                   :title "Add library"
                   :on-click (fn [e] (when (empty? (filter #(true? (% :new)) @data/libraries))
                                      (swap! data/libraries conj {:name "" :path "" :editing true :new true})))}]]]])))

(defn options-menu [button-active op-toggle]
  (let [manage-lib-state (r/atom {:mounted true})]
    (fn [button-active op-toggle]
      (when (-> @op-toggle
               nil?
               not)
        [:div
         [full-screen-backdrop button-active 98]
         (let [top   (.-offsetHeight @op-toggle) 
               right (.-offsetWidth @op-toggle)]
           [:div {:class (compose (s/options-menu top right) (if @button-active (s/options-menu-active)))}
            (doall (for [elem [{:content "Log Out" :key "log-out"
                                :click (fn [] (println "Log Out"))}]]
                     [:div {:key (str "options-" (elem :key))
                            :class (compose (s/menu-li))
                            :on-click (elem :click)}
                      (elem :content)]))])]))))

(defn options-toggle []
  (let [button-active (r/atom false)
        op-toggle (r/atom nil)]
    (fn []
      [:div {:id "options-toggle"
             :ref #(reset! op-toggle %)
             :class (compose  (s/right))
             :on-click #(toggle-visibility! button-active)}
       [:button {:class (compose (s/navbar-toggle navbar-height))}
        [:span {:class (s/sr-only)} "Options"]
        [:i {:class (compose (if @button-active (s/color-on-active s/secondary))
                             (s/toggle) (s/circle-bounding) "la la-ellipsis-v")}]]
       [options-menu button-active op-toggle]])))

(defn navbar
  "Creates a navigation bar"
  []
  (fn []
    [:div#nav-area {:class (s/navbar)}
     [:div {:class (s/above-nav (int (/ navbar-height medium-bar-divisor)))}]
     [:div {:class (s/between-above-nav (int (/ navbar-height small-bar-divisor)))}]
     [:div {:class (compose (s/pad-in-start 5) (s/navbar-nav navbar-height))}
      ;; toggle
      (case (@data/sidebar-toggle-function :function)
        toggle [sidebar-toggle]
        back   (let [{dest :dest title :title} @data/sidebar-toggle-function]
                 [back-toggle dest title])
        ;; default
        [sidebar-toggle])
      ;; logo
      [:a.navbar-brand
       {:class (compose
                (s/pad-in-start 10)
                (s/navbar-brand))
        :on-click (fn [] (reset! data/sidebar-open false)
                    (set-active! :random)
                    (reset! data/sidebar-toggle-function {:function 'toggle}))} "Warbler"]
      ;; options
      [options-toggle]]]))


(defn settings []
  (reset! data/sidebar-toggle-function {:function 'toggle})
  (fn []
    [padded-div
     {:class (compose (s/settings))}
     (doall
      (for [setting data/settings]
        [:div {:key (setting :name)}
         [:div {:class (compose (s/setting))
                :on-click (fn [] (set-active! (setting :set-active)))}
          (setting :name)]
         [:hr {:class (s/hr)}]]))]))

(defn player [state]
  (let [player-id (gensym "player_")
        seek-area (r/atom nil)]
    (fn [state]
      ;; handle
      [:div {:class (s/player)
             :ref #(reset! data/player-html %)}
       [:div {:class (s/player-handle-area)}
        [:div {:class (s/player-handle)
               :on-click (fn [] (println "handle clicked"))}]]

       ;; play position
       (let [audio (@state :audio)
             play-position (if (nil? audio) 0 (.-currentTime audio))
             duration      (if (nil? audio) 100 (.-duration audio))
             format-time (fn [t d]
                           (let [h (/ t 3600)
                                 m (/ t 60)
                                 s (rem t 60)
                                 dh (/ d 3600)
                                 dm (/ d 60)]
                             (str
                              (if (> dh 1) (gstring/format "%02d:" h))
                              (if (> dm 1) (gstring/format "%02d:" m) "00:")
                              (gstring/format "%02d" s))))]
         [:div {:class (compose (s/playing-stats))}
          [:div {:class (compose (s/player-slider-time))} (format-time play-position duration)]
          [:div {:class (compose (s/player-slider-area) (s/no-select))
                 :ref #(reset! seek-area %)
                 :on-click (fn [e] (if-let [sa @seek-area]
                                    (let [w (.-offsetWidth sa)
                                          l (.-offsetLeft sa)
                                          click-loc (-> e .-clientX)
                                          percent (/ (- click-loc l) w)
                                          time (* percent (.-duration (@state :audio)))]
                                      (set! (-> (@state :audio) .-currentTime) time))))}
           [:div {:class (compose (s/player-slider))}]
           [:div {:class (compose (s/played-slider)) :style {:width (str (* 100 (/ play-position duration)) "%")}}]
           [:div {:class (compose (s/player-cursor)) :style {:left  (str (* 100 (/ play-position duration)) "%")}}]]
          [:div {:class (compose (s/player-slider-time))} (format-time duration duration)]])

       [:div {:class (compose (s/player-bottom-area))}

        ;; controls
        [:div {:class (compose (s/player-control-area))}
         [:button {:title "Previous" :class (compose (s/no-select) (s/circle-bounding)
                                                     (s/player-button) "la la-fast-backward")
                   :on-click (fn [] nil)}]
         [:button {:title "Play" :class (compose (s/no-select) (s/circle-bounding)
                                                 (s/player-button) (s/player-play-button) "la"
                                                 (if (@state :paused) "la-play" "la-pause"))
                   :on-click (fn []  (swap! state update :paused not))
                   :on-key-press (fn [e] (if (and (= data/space-char (-> e .-charCode)) (.-stopPropagation e)) (.stopPropagation e)))}]
         [:button {:title "Previous" :class (compose (s/no-select) (s/circle-bounding)
                                                     (s/player-button) "la la-fast-forward")
                   :on-click (fn [] )}]]
        
        ;; info area
        [:div {:class (compose (s/player-info-area))}
         [:div (get-in @state [:song :title])]
         [:div (get-in @state [:album :title])]
         [:div (get-in @state [:artist :name])]]
        
        ;; volume
        [:div {:class (compose (s/right) (s/player-volume-area))}
         [:input {:type "range" :min "0" :max "1" :step "0.001" :value @data/volume
                  :class (compose (s/player-volume-slider 6))
                  :on-change (fn [e] (let [v (-> e .-target .-value)]
                                      (when (-> @state :audio nil? not)
                                        (set! (-> @state :audio .-volume) v))
                                      (reset! data/volume v)))}]]]

       ;; audio element
       (if-let [song-id (get-in @state [:song :id])]
         (let [src (str "/edn/stream/" song-id)]
           (when (@state :audio)
             (if (@state :paused) 
               (.pause (@state :audio))
               (.play (@state :audio))))
           [:audio {:id player-id :volume @data/volume
                  :ref #(swap! state assoc :audio %)
                  :autoPlay (not (@state :paused)) :src src}]))])))

(defn base []
  [:div {:class (s/futura-font)}
   [navbar]
   [sidebar]
   (case @data/active
     ;; listening
     :random     [random]
     :songs      [songs]
     :artists    [artists]
     :albums     [albums]
     
     ;; settings
     :settings   [settings]
     :manage-lib [manage-library-menu data/manage-library]
     
     ;; default
     [random])
   
   ;; player
   (when (@data/player :playing)
     [player data/player])])

