/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.smartdata.server.utils;

public class TimeUtils {
  public static TimeGranularity getGranularity(long length) {
    if (length / Constants.ONE_DAY_IN_MILLIS > 0) {
      return TimeGranularity.DAY;
    } else if (length / Constants.ONE_HOUR_IN_MILLIS > 0) {
      return TimeGranularity.HOUR;
    } else if (length / Constants.ONE_MINUTE_IN_MILLIS > 0) {
      return TimeGranularity.MINUTE;
    } else {
      return TimeGranularity.SECOND;
    }
  }

  public static TimeGranularity getFineGarinedGranularity(TimeGranularity granularity) {
    switch (granularity) {
      case YEAR:
        return TimeGranularity.MONTH;
      case MONTH:
        return TimeGranularity.WEEK;
      case WEEK:
        return TimeGranularity.DAY;
      case DAY:
        return TimeGranularity.HOUR;
      case HOUR:
        return TimeGranularity.MINUTE;
      case MINUTE:
        return TimeGranularity.SECOND;
    }
    return TimeGranularity.SECOND;
  }
}
