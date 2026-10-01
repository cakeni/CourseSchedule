import { common } from '@kit.AbilityKit';
import { reminderAgentManager } from '@kit.BackgroundTasksKit';
import { notificationManager } from '@kit.NotificationKit';
import { preferences } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { Course, ScheduleState, courseInWeek, reminderOccurrences, weekStart } from '../model/ScheduleCore';
import { START_TIMES } from '../pages/ScheduleTheme';

function localDate(date: Date): reminderAgentManager.LocalDateTime {
  return { year: date.getFullYear(), month: date.getMonth() + 1, day: date.getDate(),
    hour: date.getHours(), minute: date.getMinutes(), second: date.getSeconds() };
}

function request(course: Course, date: Date, last: Date): reminderAgentManager.ReminderRequestCalendar {
  return {
    reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_CALENDAR,
    dateTime: localDate(date), endDateTime: localDate(last), daysOfWeek: [(date.getDay() + 6) % 7 + 1],
    title: course.courseName, content: `第${course.startSection}-${course.endSection}节 · ${course.classroom || '未提供教室'}${course.teacher ? ' · ' + course.teacher : ''}`,
    expiredContent: '课程即将开始', notificationId: course.id, ringDuration: 0,
    wantAgent: { pkgName: 'com.courseschedule.harmonyos', abilityName: 'EntryAbility' },
    actionButton: [{ title: '关闭', type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_CLOSE }],
    slotType: notificationManager.SlotType.SOCIAL_COMMUNICATION
  };
}

let scheduling: Promise<string> = Promise.resolve('');

export function reminderError(error: BusinessError): string {
  if (error.code === 1700002) return '系统未允许代理提醒，或有效提醒已达上限（1700002）';
  return error.message ? `${error.message}（${error.code}）` : String(error);
}

export function syncReminders(context: common.UIAbilityContext, state: ScheduleState): Promise<string> {
  scheduling = scheduling.catch(() => '').then(() => syncNow(context, state));
  return scheduling;
}

async function syncNow(context: common.UIAbilityContext, state: ScheduleState): Promise<string> {
  const scheduled: number[] = [];
  try {
    const previous = await reminderAgentManager.getAllValidReminders();
    for (const reminder of previous) {
      if (reminder.reminderReq.notificationId !== 2147480000) await reminderAgentManager.cancelReminder(reminder.reminderId);
    }
    if (!state.settings.reminderEnabled) return '课程提醒已暂停';
    if (!(await notificationManager.isNotificationEnabled())) return '系统通知未开启，点击授权';
    const starts = state.settings.sectionTimes.length === 12 ? state.settings.sectionTimes : START_TIMES;
    const occurrences = reminderOccurrences(state, starts);
    const store = await preferences.getPreferences(context, 'qing_schedule');
    let delivered = JSON.parse(await store.get('reminder_deliveries', '[]') as string) as string[];
    const ids: number[] = [];
    for (const occurrence of occurrences) {
      const course = occurrence.course;
      if (ids.includes(course.id)) continue;
      const courseTimes = occurrences.filter(o => o.course.id === course.id);
      ids.push(course.id);
      // A catch-up notification uses the real class occurrence as its stable identity.
      // Weekly requests always retain the original clock time, even when opened shortly before class.
      const naturalTime = occurrence.classStart - course.reminderMinutes * 60000;
      if (naturalTime <= Date.now()) {
        const key = `${course.id}:${occurrence.classStart}`;
        if (!delivered.includes(key)) {
          await notificationManager.publish({ id: course.id, content: {
            notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
            normal: { title: course.courseName, text: request(course, new Date(), new Date()).content || '' }
          } });
          delivered.push(key); delivered = delivered.slice(-500);
          await store.put('reminder_deliveries', JSON.stringify(delivered)); await store.flush();
        }
      }
      const future = courseTimes.filter(o => o.classStart - course.reminderMinutes * 60000 > Date.now());
      if (!future.length) continue;
      const first = new Date(future[0].classStart - course.reminderMinutes * 60000);
      const last = new Date(future[future.length - 1].classStart - course.reminderMinutes * 60000 + 1000);
      const id = await reminderAgentManager.publishReminder(request(course, first, last));
      scheduled.push(id);
      // Weekly system reminders remain active after app termination. Exclude parity gaps explicitly.
      for (let week = course.startWeek; week <= Math.min(course.endWeek, state.semester.totalWeeks); week++) {
        if (courseInWeek(course, week)) continue;
        const skipped = weekStart(state.semester, week);
        skipped.setDate(skipped.getDate() + course.dayOfWeek - 1);
        const time = starts[course.startSection - 1].split(':');
        skipped.setHours(Number(time[0]), Number(time[1]), 0, 0);
        skipped.setTime(skipped.getTime() - course.reminderMinutes * 60000);
        await reminderAgentManager.addExcludeDate(id, skipped);
      }
    }
    return ids.length ? `已安排 ${ids.length} 条课程提醒\n下次提醒：${new Date(occurrences[0].reminderTime).toLocaleString()}` : '当前没有待提醒的课次';
  } catch (error) {
    for (const id of scheduled) { try { await reminderAgentManager.cancelReminder(id); } catch (_) {} }
    return '系统提醒排程失败：' + reminderError(error as BusinessError);
  }
}

export async function authorizeNotifications(context: common.UIAbilityContext): Promise<void> {
  try { await notificationManager.requestEnableNotification(context); }
  catch (error) {
    if ((error as BusinessError).code !== 1600004) throw new Error(reminderError(error as BusinessError));
    await notificationManager.openNotificationSettingsWithResult(context);
  }
  if (!(await notificationManager.isNotificationEnabled())) throw new Error('通知未开启，请在通知管理中启用晴课表');
}

export async function testReminder(context: common.UIAbilityContext): Promise<void> {
  if (!(await notificationManager.isNotificationEnabled())) await authorizeNotifications(context);
  const reminder: reminderAgentManager.ReminderRequestTimer = {
    reminderType: reminderAgentManager.ReminderType.REMINDER_TYPE_TIMER, triggerTimeInSeconds: 10,
    title: '测试提醒', content: '提醒已送达。课程提醒会按课程设置发送。', ringDuration: 0,
    notificationId: 2147480000, wantAgent: { pkgName: 'com.courseschedule.harmonyos', abilityName: 'EntryAbility' },
    actionButton: [{ title: '关闭', type: reminderAgentManager.ActionButtonType.ACTION_BUTTON_TYPE_CLOSE }]
  };
  await reminderAgentManager.publishReminder(reminder);
}
