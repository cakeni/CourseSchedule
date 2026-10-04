// Match the Android semantic palette and course color order.
export interface SchedulePalette {
  primary: string;
  container: string;
  background: string;
  surface: string;
  variant: string;
  text: string;
  secondary: string;
  tertiary: string;
  divider: string;
  gradientStart: string;
  gradientEnd: string;
  navigation: string;
  navigationOutline: string;
  courses: string[];
}

export const LIGHT: SchedulePalette = {
  primary: '#3E628C', container: '#DCE8F5', background: '#EDF2FA', surface: '#FAFCFF',
  variant: '#F3F6FB', text: '#1C2733', secondary: '#667487', tertiary: '#8A96A5',
  divider: '#DCE4EF', gradientStart: '#F3F3FB', gradientEnd: '#DFE9F7',
  navigation: '#EBF4F7FC', navigationOutline: '#80FFFFFF',
  courses: ['#E78387', '#DD86A8', '#A58BE2', '#8F82D3', '#819DDB', '#68A6DA',
    '#73B4DE', '#70C2C7', '#68B8A8', '#7BB38C', '#9CB97B', '#B0BE73',
    '#CDB36C', '#DAA56E', '#E28C70', '#B39182']
};

export const DARK: SchedulePalette = {
  primary: '#2874D0', container: '#202020', background: '#000000', surface: '#171717',
  variant: '#222222', text: '#F2F2F2', secondary: '#B8B8B8', tertiary: '#858585',
  divider: '#323232', gradientStart: '#050505', gradientEnd: '#000000',
  navigation: '#FA121212', navigationOutline: '#FF323232',
  courses: ['#A84F58', '#9B4F70', '#7657AE', '#61509E', '#5169A8', '#3D73A0',
    '#3E789B', '#3D7E82', '#3E786D', '#4D7959', '#667844', '#74783F',
    '#857238', '#906837', '#985844', '#755B50']
};

export const DAY_NAMES: string[] = ['一', '二', '三', '四', '五', '六', '日'];
export const SECTIONS: number[] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
export const COLOR_INDEXES: number[] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
export const START_TIMES: string[] = ['08:00', '08:50', '09:50', '10:40', '11:30', '14:30',
  '15:20', '16:20', '17:10', '19:00', '19:50', '20:40'];
export const END_TIMES: string[] = ['08:45', '09:35', '10:35', '11:25', '12:15', '15:15',
  '16:05', '17:05', '17:55', '19:45', '20:35', '21:25'];
