(ns braid.client.state.helpers
  (:require [cljs-utils.core :refer [flip]]
            [clojure.set :as set]
            [cljs-uuid-utils.core :as uuid]))

(defn key-by-id [coll]
  (into {} (map (juxt :id identity)) coll))

(defn key-by [k coll]
  (into {} (map (juxt k identity)) coll))

; ALL HELPERS BELOW SHOULD TAKE STATE AS FIRST ARG

; GETTERS

(defn get-user-preferences [state]
  (get state :preferences))

(defn current-user-id [state]
  (get-in state [:session :user-id]))

(defn get-open-group-id [state]
  (get state :open-group-id))

; SETTERS

; error

(defn display-error
  ([state err-key msg]
   (display-error state err-key msg :error))
  ([state err-key msg cls]
   (update-in state [:errors] #(conj % [err-key msg cls]))))

(defn clear-error [state err-key]
  (update-in state [:errors] #(into [] (remove (fn [[k _]] (= k err-key))) %)))

; page

(defn set-group-and-page [state group-id page-id]
  (assoc state
    :open-group-id group-id
    :page page-id))

(defn set-page-error [state bool]
  (assoc-in state [:page :error?] bool))

; session

(defn set-session [state data]
  (assoc-in state [:session] data))

; user

(defn set-preferences [state prefs]
  (update-in state [:preferences] merge prefs))

; users

(defn update-user-avatar [state user-id avatar]
  (assoc-in state [:users user-id :avatar] avatar))

; threads and messages

(defn set-threads [state threads]
  (assoc-in state [:threads] (key-by-id threads)))

(defn reset-new-thread-id [state]
  (assoc-in state [:new-thread-id] (uuid/make-random-squuid)))

(defn maybe-reset-new-thread-id [state thread-id]
  (if (= thread-id (:new-thread-id state))
    (reset-new-thread-id state)
    state))

(defn set-open-threads [state threads]
  (-> state
      (assoc-in [:group-threads] (into {}
                                       (map (fn [[g t]]
                                              [g (into #{} (map :id) t)]))
                                       (group-by :group-id threads)))
      (assoc-in [:user :open-thread-ids] (set (map :id threads)))))

(defn focus-thread [state thread-id]
  (assoc-in state [:focused-thread-id] thread-id))

(defn update-thread-last-open-at [state thread-id]
  (if-let [thread (get-in state [:threads thread-id])]
    (let [latest-message (->> (thread :messages)
                              (map :created-at)
                              (reduce max (js/Date. 0)))
          new-last-open (js/Date. (inc (.getTime latest-message)))]
      (update-in state [:threads thread-id :last-open-at] (constantly new-last-open)))
    state))

(defn maybe-create-thread [state thread-id group-id]
  (let [state (update-in state [:user :open-thread-ids] #(conj % thread-id))]
    (if-not (get-in state [:threads thread-id])
      (-> state
          (assoc-in [:threads thread-id] {:id thread-id
                                          :group-id group-id
                                          :messages []
                                          :tag-ids #{}
                                          :mentioned-ids #{}})
          (update-in [:group-threads group-id] #(conj (set %) thread-id)))
      state)))

(defn add-message [state message]
  (-> state
      (maybe-create-thread (message :thread-id) (message :group-id))
      (update-in [:threads (message :thread-id) :messages] #(conj % message))
      (update-thread-last-open-at (message :thread-id))
      (update-in [:threads (message :thread-id) :tag-ids]
                 (partial set/union (set (message :mentioned-tag-ids))))
      (update-in [:threads (message :thread-id) :mentioned-ids]
                 (partial set/union (set (message :mentioned-user-ids))))))

; tags

(defn subscribe-to-tag [state tag-id]
  (update-in state [:user :subscribed-tag-ids] #(conj % tag-id)))

(defn add-tags [state tags]
  (update-in state [:tags] #(merge % (key-by-id tags))))

(defn set-subscribed-tag-ids [state tag-ids]
  (assoc-in state [:user :subscribed-tag-ids] (set tag-ids)))

; groups

(defn set-groups [state groups]
  (assoc-in state [:groups] (key-by-id groups)))

(defn add-group [state group]
  (update-in state [:groups] (flip assoc (group :id) group)))

(defn remove-group [state group-id]
  (let [group-threads (get-in state [:group-threads group-id])]
    (-> state
        (update-in [:threads] #(apply dissoc % group-threads))
        (update-in [:group-threads] (flip dissoc group-id))
        (update-in [:groups] (flip dissoc group-id)))))

; invitations

(defn set-invitations [state invitations]
  (assoc-in state [:invitations] invitations))

(defn remove-invite [state invite]
  (update-in state [:invitations] (partial remove (partial = invite))))
