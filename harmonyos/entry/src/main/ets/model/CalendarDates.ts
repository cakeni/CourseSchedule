export function calendarMonthDays(year: number, month: number): number[] {
  const offset = (new Date(year, month, 1).getDay() + 6) % 7;
  const count = new Date(year, month + 1, 0).getDate();
  return Array.from({ length: 42 }, (_, index: number) => {
    const day = index - offset + 1;
    return day >= 1 && day <= count ? day : 0;
  });
}

export function calendarDateText(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
