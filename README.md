# SensorLog
- スマホの加速度・角度・回転の速さを coordinate.txt に記録(500行ごとに切り替え)
- http://127.0.0.1:8765/latest?n=500 で最新行を返す
- VRChat OSC: スマホの向きを片足トラッカー(/tracking/trackers/1/rotation)として Quest の 9000 番へ送る(初期型)
