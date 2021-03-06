/*
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */

package com.todoroo.astrid.dao;

import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.transform;
import static com.todoroo.andlib.sql.SqlConstants.COUNT;
import static com.todoroo.andlib.utility.AndroidUtilities.atLeastLollipop;
import static com.todoroo.andlib.utility.DateUtilities.now;
import static org.tasks.db.DbUtils.batch;

import androidx.paging.DataSource;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.room.Transaction;
import androidx.room.Update;
import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Field;
import com.todoroo.andlib.sql.Functions;
import com.todoroo.astrid.api.Filter;
import com.todoroo.astrid.api.PermaSql;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.helper.UUIDHelper;
import java.util.Collections;
import java.util.List;
import org.tasks.BuildConfig;
import org.tasks.data.Place;
import org.tasks.data.TaskContainer;
import org.tasks.jobs.WorkManager;
import timber.log.Timber;

@Dao
public abstract class TaskDao {

  public static final String TRANS_SUPPRESS_REFRESH = "suppress-refresh";

  private final Database database;

  private WorkManager workManager;

  public TaskDao(Database database) {
    this.database = database;
  }

  public void initialize(WorkManager workManager) {
    this.workManager = workManager;
  }

  public List<Task> needsRefresh() {
    return needsRefresh(now());
  }

  @Query(
      "SELECT * FROM tasks WHERE completed = 0 AND deleted = 0 AND (hideUntil > :now OR dueDate > :now)")
  abstract List<Task> needsRefresh(long now);

  @Query("SELECT * FROM tasks WHERE _id = :id LIMIT 1")
  public abstract Task fetch(long id);

  @Query("SELECT * FROM tasks WHERE _id IN (:taskIds)")
  public abstract List<Task> fetch(List<Long> taskIds);

  @Query("SELECT COUNT(1) FROM tasks WHERE timerStart > 0 AND deleted = 0")
  public abstract int activeTimers();

  @Query("SELECT tasks.* FROM tasks INNER JOIN notification ON tasks._id = notification.task")
  public abstract List<Task> activeNotifications();

  @Query("SELECT * FROM tasks WHERE remoteId = :remoteId")
  public abstract Task fetch(String remoteId);

  @Query("SELECT * FROM tasks WHERE completed = 0 AND deleted = 0")
  abstract List<Task> getActiveTasks();

  @Query("SELECT * FROM tasks WHERE hideUntil < (strftime('%s','now')*1000)")
  abstract List<Task> getVisibleTasks();

  @Query(
      "SELECT * FROM tasks WHERE remoteId IN (:remoteIds) "
          + "AND recurrence IS NOT NULL AND LENGTH(recurrence) > 0")
  public abstract List<Task> getRecurringTasks(List<String> remoteIds);

  @Query("UPDATE tasks SET completed = :completionDate " + "WHERE remoteId = :remoteId")
  public abstract void setCompletionDate(String remoteId, long completionDate);

  @Query("UPDATE tasks SET snoozeTime = :millis WHERE _id in (:taskIds)")
  public abstract void snooze(List<Long> taskIds, long millis);

  @Query(
      "SELECT tasks.* FROM tasks "
          + "LEFT JOIN google_tasks ON tasks._id = google_tasks.gt_task "
          + "WHERE gt_list_id IN (SELECT gtl_remote_id FROM google_task_lists WHERE gtl_account = :account)"
          + "AND (tasks.modified > google_tasks.gt_last_sync OR google_tasks.gt_remote_id = '' OR google_tasks.gt_deleted > 0) "
          + "ORDER BY CASE WHEN gt_parent = 0 THEN 0 ELSE 1 END, gt_order ASC")
  public abstract List<Task> getGoogleTasksToPush(String account);

  @Query(
      "SELECT tasks.* FROM tasks "
          + "LEFT JOIN caldav_tasks ON tasks._id = caldav_tasks.cd_task "
          + "WHERE caldav_tasks.cd_calendar = :calendar "
          + "AND tasks.modified > caldav_tasks.cd_last_sync")
  public abstract List<Task> getCaldavTasksToPush(String calendar);

  @Query(
      "SELECT * FROM TASKS "
          + "WHERE completed = 0 AND deleted = 0 AND (notificationFlags > 0 OR notifications > 0)")
  public abstract List<Task> getTasksWithReminders();

  // --- SQL clause generators

  @Query("SELECT * FROM tasks")
  public abstract List<Task> getAll();

  @Query("SELECT calendarUri FROM tasks " + "WHERE calendarUri IS NOT NULL AND calendarUri != ''")
  public abstract List<String> getAllCalendarEvents();

  @Query("UPDATE tasks SET calendarUri = '' " + "WHERE calendarUri IS NOT NULL AND calendarUri != ''")
  public abstract int clearAllCalendarEvents();

  @Query(
      "SELECT calendarUri FROM tasks "
          + "WHERE completed > 0 AND calendarUri IS NOT NULL AND calendarUri != ''")
  public abstract List<String> getCompletedCalendarEvents();

  @Query(
      "UPDATE tasks SET calendarUri = '' "
          + "WHERE completed > 0 AND calendarUri IS NOT NULL AND calendarUri != ''")
  public abstract int clearCompletedCalendarEvents();

  @Transaction
  public List<TaskContainer> fetchTasks(QueryCallback callback) {
    long start = BuildConfig.DEBUG ? now() : 0;
    boolean includeGoogleSubtasks = atLeastLollipop() && hasGoogleTaskSubtasks();
    boolean includeCaldavSubtasks = atLeastLollipop() && hasSubtasks();
    List<String> queries = callback.getQueries(includeGoogleSubtasks, includeCaldavSubtasks);
    SupportSQLiteDatabase db = database.getOpenHelper().getWritableDatabase();
    int last = queries.size() - 1;
    for (int i = 0 ; i < last ; i++) {
      db.execSQL(queries.get(i));
    }
    List<TaskContainer> result = fetchTasks(new SimpleSQLiteQuery(queries.get(last)));
    Timber.v("%sms: %s", now() - start, Joiner.on(";").join(queries));
    return result;
  }

  @RawQuery
  abstract List<TaskContainer> fetchTasks(SimpleSQLiteQuery query);

  @RawQuery
  abstract int count(SimpleSQLiteQuery query);

  @Query("SELECT EXISTS(SELECT 1 FROM tasks WHERE parent > 0 AND deleted = 0)")
  abstract boolean hasSubtasks();

  @Query(
      "SELECT EXISTS(SELECT 1 FROM google_tasks "
          + "INNER JOIN tasks ON gt_task = _id "
          + "WHERE deleted = 0 AND gt_parent > 0 AND gt_deleted = 0)")
  abstract boolean hasGoogleTaskSubtasks();

  @RawQuery(observedEntities = {Place.class})
  public abstract DataSource.Factory<Integer, TaskContainer> getTaskFactory(
      SimpleSQLiteQuery query);

  public void touch(Long id) {
    touch(ImmutableList.of(id));
  }

  public void touch(List<Long> ids) {
    touchInternal(ids);
    workManager.sync(false);
  }

  @Query("UPDATE tasks SET modified = strftime('%s','now')*1000 WHERE _id in (:ids)")
  abstract void touchInternal(List<Long> ids);

  @Query(
      "UPDATE tasks SET parent = IFNULL(("
          + " SELECT parent._id FROM tasks AS parent"
          + " WHERE parent.remoteId = tasks.parent_uuid AND parent.deleted = 0), 0)"
          + "WHERE parent_uuid IS NOT NULL AND parent_uuid != ''")
  public abstract void updateParents();

  @Query(
      "UPDATE tasks SET parent_uuid = "
          + "  (SELECT parent.remoteId FROM tasks AS parent WHERE parent._id = tasks.parent)"
          + "  WHERE parent > 0 AND _id IN (:tasks)")
  public abstract void updateParentUids(List<Long> tasks);

  @Query("UPDATE tasks SET parent = :parent, parent_uuid = :parentUuid WHERE _id IN (:children)")
  public abstract void setParent(long parent, String parentUuid, List<Long> children);

  @Transaction
  public List<Task> fetchChildren(long id) {
    return fetch(getChildren(id));
  }

  public List<Long> getChildren(long id) {
    return getChildren(Collections.singletonList(id));
  }

  public List<Long> getChildren(List<Long> ids) {
    return atLeastLollipop()
        ? getChildrenRecursive(ids)
        : Collections.emptyList();
  }

  @Query(
      "WITH RECURSIVE "
          + " recursive_tasks (task) AS ( "
          + " SELECT _id "
          + " FROM tasks "
          + "WHERE parent IN (:ids)"
          + "UNION ALL "
          + " SELECT _id "
          + " FROM tasks "
          + " INNER JOIN recursive_tasks "
          + "  ON recursive_tasks.task = tasks.parent"
          + " WHERE tasks.deleted = 0)"
          + "SELECT task FROM recursive_tasks")
  abstract List<Long> getChildrenRecursive(List<Long> ids);

  public List<Long> findChildrenInList(List<Long> ids) {
    List<Long> result = newArrayList(ids);
    result.retainAll(getChildren(ids));
    return result;
  }

  @Query("UPDATE tasks SET collapsed = :collapsed WHERE _id = :id")
  public abstract void setCollapsed(long id, boolean collapsed);

  @Transaction
  public void setCollapsed(List<TaskContainer> tasks, boolean collapsed) {
    batch(
        transform(filter(tasks, TaskContainer::hasChildren), TaskContainer::getId),
        l -> collapse(l, collapsed));
  }

  @Query("UPDATE tasks SET collapsed = :collapsed WHERE _id IN (:ids)")
  abstract void collapse(List<Long> ids, boolean collapsed);

  /**
   * Saves the given task to the database.getDatabase(). Task must already exist. Returns true on
   * success.
   */
  public void save(Task task) {
    save(task, fetch(task.getId()));
  }

  // --- save

  // TODO: get rid of this super-hack
  public void save(Task task, Task original) {
    if (!task.insignificantChange(original)) {
      task.setModificationDate(now());
    }
    if (update(task) == 1) {
      workManager.afterSave(task, original);
    }
  }

  @Insert
  abstract long insert(Task task);

  @Update
  abstract int update(Task task);

  public void createNew(Task task) {
    task.id = null;
    if (task.created == 0) {
      task.created = now();
    }
    if (Task.isUuidEmpty(task.remoteId)) {
      task.remoteId = UUIDHelper.newUUID();
    }
    long insert = insert(task);
    task.setId(insert);
  }

  @Query(
      "SELECT * FROM tasks "
          + "WHERE completed = 0 AND deleted = 0 AND hideUntil < (strftime('%s','now')*1000) "
          + "ORDER BY (CASE WHEN (dueDate=0) THEN (strftime('%s','now')*1000)*2 ELSE ((CASE WHEN (dueDate / 1000) % 60 > 0 THEN dueDate ELSE (dueDate + 43140000) END)) END) + 172800000 * importance ASC "
          + "LIMIT 100")
  public abstract List<Task> getAstrid2TaskProviderTasks();

  public int count(Filter filter) {
    SimpleSQLiteQuery query = getQuery(filter.sqlQuery, COUNT);
    long start = BuildConfig.DEBUG ? now() : 0;
    int count = count(query);
    Timber.v("%sms: %s", now() - start, query.getSql());
    return count;
  }

  public List<Task> fetchFiltered(Filter filter) {
    return fetchFiltered(filter.getSqlQuery());
  }

  public List<Task> fetchFiltered(String queryTemplate) {
    SimpleSQLiteQuery query = getQuery(queryTemplate, Task.FIELDS);
    long start = BuildConfig.DEBUG ? now() : 0;
    List<TaskContainer> tasks = fetchTasks(query);
    Timber.v("%sms: %s", now() - start, query.getSql());
    return transform(tasks, TaskContainer::getTask);
  }

  private static SimpleSQLiteQuery getQuery(String queryTemplate, Field... fields) {
    return new SimpleSQLiteQuery(
        com.todoroo.andlib.sql.Query.select(fields)
            .withQueryTemplate(PermaSql.replacePlaceholdersForQuery(queryTemplate))
            .from(Task.TABLE)
            .toString());
  }

  /** Generates SQL clauses */
  public static class TaskCriteria {

    /** @return tasks that have not yet been completed or deleted */
    public static Criterion activeAndVisible() {
      return Criterion.and(
          Task.COMPLETION_DATE.lte(0),
          Task.DELETION_DATE.lte(0),
          Task.HIDE_UNTIL.lte(Functions.now()));
    }
  }

  public interface QueryCallback {
    List<String> getQueries(boolean includeGoogleTaskSubtasks, boolean includeCaldavSubtasks);
  }
}
