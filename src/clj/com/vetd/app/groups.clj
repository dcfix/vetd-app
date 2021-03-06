(ns com.vetd.app.groups
  (:require [com.vetd.app.db :as db]
            [com.vetd.app.common :as com]
            [com.vetd.app.util :as ut]
            [com.vetd.app.journal :as journal]
            [com.vetd.app.hasura :as ha]
            [clojure.string :as st]))

(defn select-groups-by-admins
  "Get all the groups of which any of org-ids is an admin."
  [org-ids]
  (-> [[:groups {:admin-org-id org-ids}
        [:id]]]
      ha/sync-query
      vals
      first))

(defn insert-group-org-membership
  [org-id group-id]
  (let [[id idstr] (ut/mk-id&str)]
    (-> (db/insert! :group_org_memberships
                    {:id id
                     :idstr idstr
                     :created (ut/now-ts)
                     :updated (ut/now-ts)
                     :org_id org-id
                     :group_id group-id})
        first)))

(defn select-group-org-memb-by-ids
  [org-id group-id]
  (-> [[:group-org-memberships
        {:group-id group-id
         :org-id org-id}
        [:id :group-id :org-id :created]]]
      ha/sync-query
      vals
      ffirst))

(defn create-or-find-group-org-memb
  [org-id group-id]
  (if-let [memb (select-group-org-memb-by-ids org-id group-id)]
    [false memb]
    (when-let [inserted (insert-group-org-membership org-id group-id)]
      (let [{:keys [gname]} (some-> [[:groups {:id group-id}
                                      [:gname]]]
                                    ha/sync-query
                                    vals
                                    ffirst)
            {:keys [oname]} (some-> [[:orgs {:id org-id}
                                      [:oname]]]
                                    ha/sync-query
                                    vals
                                    ffirst)]
        (do (journal/push-entry {:jtype :org-added-to-community
                                 :org-id org-id
                                 :group-id group-id
                                 :group-name gname
                                 :org-name oname})
            (com/sns-publish :ui-misc
                             "Org Added to Community"
                             (str oname " (org) was added to "
                                  gname " (community)"))
            [true inserted])))))

(defn delete-group-org-memb
  [group-org-memb-id]
  (db/hs-exe! {:delete-from :group_org_memberships
               :where [:= :id group-org-memb-id]}))

(defn insert-group [group-name admin-org-id]
  (let [[id idstr] (ut/mk-id&str)]
    (-> (db/insert! :groups
                    {:id id
                     :idstr idstr
                     :created (ut/now-ts)
                     :updated (ut/now-ts)
                     :gname group-name
                     :admin_org_id admin-org-id})
        first)))

(defn delete-group-discount-by-ids
  [group-id product-id]
  (db/update-deleted-where
   :group_discounts
   [:and
    [:= :group_id group-id]
    [:= :product_id product-id]]))

(defn delete-group-discount
  [group-discount-id]
  (db/update-deleted :group_discounts
                     group-discount-id))

(defn insert-group-discount
  [group-id product-id descr redemption-descr & [origin-id]]
  (let [[id idstr] (ut/mk-id&str)]
    (-> (db/insert! :group_discounts
                    {:id id
                     :idstr idstr
                     :created (ut/now-ts)
                     :updated (ut/now-ts)
                     :group_id group-id
                     :product_id product-id
                     :descr descr
                     :redemption_descr redemption-descr
                     :origin_id origin-id})
        first)))

(defn set-group-discount
  [group-id product-id descr redemption-descr]
  (delete-group-discount-by-ids group-id product-id)
  (insert-group-discount group-id product-id descr redemption-descr))

(defmethod com/handle-ws-inbound :create-group
  [{:keys [group-name admin-org-id]} ws-id sub-fn]
  (insert-group group-name admin-org-id)
  {})

(defmethod com/handle-ws-inbound :add-org-to-group
  [{:keys [org-id group-id]} ws-id sub-fn]
  (create-or-find-group-org-memb org-id group-id)
  {})

(defmethod com/handle-ws-inbound :g/remove-org
  [{:keys [org-id group-id]} ws-id sub-fn]
  (when-let [{:keys [id]} (select-group-org-memb-by-ids org-id group-id)]
    (delete-group-org-memb id))
  {})

(defmethod com/handle-ws-inbound :g/set-discount
  [{:keys [group-id product-id descr]} ws-id sub-fn]
  (set-group-discount group-id product-id descr)
  {})

(defmethod com/handle-ws-inbound :g/add-discount
  [{:keys [group-id product-id descr redemption-descr] :as req} ws-id sub-fn]
  (journal/push-entry (assoc req
                             :jtype :add-discount))
  (insert-group-discount group-id product-id descr redemption-descr)
  {})

(defmethod com/handle-ws-inbound :g/delete-discount
  [{:keys [discount-id]} ws-id sub-fn]
  (delete-group-discount discount-id)
  {})
