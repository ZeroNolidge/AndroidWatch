package com.androidwatch.companion

import java.util.UUID

/**
 * Wire format shared with the watch app (`phonelink`). Keep both copies in sync.
 *
 * One JSON object per line over a Bluetooth RFCOMM socket. "t" is the message type.
 *
 * phone → watch
 *   hello   {device, battery}
 *   clear   {}                               (sent before a full sync)
 *   post    {key, app, title, text, when, call, actions:[{i, title, reply}]}
 *   remove  {key}
 *
 * watch → phone
 *   sync    {}                               (send me everything that's active)
 *   action  {key, i}                         (tap action number i)
 *   reply   {key, i, text}                   (reply through action i)
 *   dismiss {key}
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("6f3c9a52-1d7e-4b8a-9c41-a2e57f0b3d19")
    const val SERVICE_NAME = "AndroidWatch Link"
}
