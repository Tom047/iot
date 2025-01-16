/* mbed Microcontroller Library
 * Copyright (c) 2017-2019 ARM Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "platform/Callback.h"
#include "events/EventQueue.h"
#include "ble/BLE.h"
#include "gatt_server_process.h"
#include "mbed-trace/mbed_trace.h"
#include <cstddef>
#include <cstdint>
#include <stdint.h>
#include "drivers/PwmOut.h"

using mbed::callback;
using namespace std::literals::chrono_literals;

/**
 * A Clock service that demonstrate the GattServer features.
 *
 * The clock service host three characteristics that model the current hour,
 * minute and second of the clock. The value of the second characteristic is
 * incremented automatically by the system.
 *
 * A client can subscribe to updates of the clock characteristics and get
 * notified when one of the value is changed. Clients can also change value of
 * the second, minute and hour characteristric.
 */
class NoteService : public ble::GattServer::EventHandler {
public:
    NoteService() :
        _note_char("485f4145-52b9-4644-af1f-7a6b9322490f",
            &_initial_note,
            sizeof(_initial_note),
            sizeof(_initial_note),
            GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_WRITE),
        _note_service(
            /* uuid */ "51311102-030e-485f-b122-f8f381aa84ed",
            /* characteristics */ _note_char_array,
            /* numCharacteristics */ sizeof(_note_char_array) /
                                     sizeof(_note_char_array[0])
        )
    {
        /* update internal pointers (value, descriptors and characteristics array) */
        _note_char_array[0] = &_note_char;

        /* setup authorization handlers */
        _note_char.setWriteAuthorizationCallback(this, &NoteService::authorize_client_write);
    }

    void start(BLE &ble, events::EventQueue &event_queue)
    {
        _server = &ble.gattServer();
        _event_queue = &event_queue;

        printf("Registering demo service\r\n");
        ble_error_t err = _server->addService(_note_service);

        if (err) {
            printf("Error %u during demo service registration.\r\n", err);
            return;
        }

        /* register handlers */
        _server->setEventHandler(this);

        printf("clock service registered\r\n");
        printf("service handle: %u\r\n", _note_service.getHandle());
    }

    /* GattServer::EventHandler */
private:
    /**
     * Handler called when a notification or an indication has been sent.
     */
    void onDataSent(const GattDataSentCallbackParams &params) override
    {
        printf("sent updates\r\n");
    }

    /**
     * Handler called after an attribute has been written.
     */
    void onDataWritten(const GattWriteCallbackParams &params) override
    {
        if (params.handle == _note_char.getValueHandle()) {
            printf("Note data written (len = %u): ", params.len);
            for (size_t i = 0; i < params.len; ++i) {
                printf("%02X", params.data[i]);
            }
            printf("\r\n");

            _event_queue->call(callback(this, &NoteService::playNotes), params.data, params.len);
        } 

        
    }

    /**
     * Handler called after an attribute has been read.
     */
    void onDataRead(const GattReadCallbackParams &params) override
    {
        printf("data read:\r\n");
        printf("connection handle: %u\r\n", params.connHandle);
        printf("attribute handle: %u", params.handle);
        if (params.handle == _note_char.getValueHandle()) {
            printf(" (hour characteristic)\r\n");
        } else {
            printf("\r\n");
        }
    }

    /**
     * Handler called after a client has subscribed to notification or indication.
     *
     * @param handle Handle of the characteristic value affected by the change.
     */
    void onUpdatesEnabled(const GattUpdatesEnabledCallbackParams &params) override
    {
        printf("update enabled on handle %d\r\n", params.attHandle);
    }

    /**
     * Handler called after a client has cancelled his subscription from
     * notification or indication.
     *
     * @param handle Handle of the characteristic value affected by the change.
     */
    void onUpdatesDisabled(const GattUpdatesDisabledCallbackParams &params) override
    {
        printf("update disabled on handle %d\r\n", params.attHandle);
    }

    /**
     * Handler called when an indication confirmation has been received.
     *
     * @param handle Handle of the characteristic value that has emitted the
     * indication.
     */
    void onConfirmationReceived(const GattConfirmationReceivedCallbackParams &params) override
    {
        printf("confirmation received on handle %d\r\n", params.attHandle);
    }

private:
    /**
     * Handler called when a write request is received.
     *
     * This handler verify that the value submitted by the client is valid before
     * authorizing the operation.
     */
    void authorize_client_write(GattWriteAuthCallbackParams *e)
    {
        printf("characteristic %u write authorization\r\n", e->handle);

        if (e->len != 1) {
            printf("Error invalid len\r\n");
            e->authorizationReply = AUTH_CALLBACK_REPLY_ATTERR_INVALID_ATT_VAL_LENGTH;
            return;
        }

        if ((e->data[0] >= 60) ||
            ((e->data[0] >= 24) && (e->handle == _note_char.getValueHandle()))) {
            printf("Error invalid data\r\n");
            e->authorizationReply = AUTH_CALLBACK_REPLY_ATTERR_WRITE_NOT_PERMITTED;
            return;
        }

        e->authorizationReply = AUTH_CALLBACK_REPLY_SUCCESS;
    }

    void playNotes(const uint8_t* data, size_t len)
    {
        PwmOut buzzer(D7);

        for (size_t i = 0; i < len; i++)
        {
            uint8_t note = data[i];

            float frequency = noteToFrequency(note);

            printf("Playing note %u: %.2f Hz\r\n", note, frequency);

            buzzer.period(1.0f / frequency);
            buzzer.write(0.5f);
            ThisThread::sleep_for(200ms);
            buzzer.write(0.0f);
            ThisThread::sleep_for(50ms);
        }
    }

    float noteToFrequency(uint8_t note)
    {
        const float middleCFreq = 261.63f; // Frequency fot note 60
        int semitoneOffset = note - 60;
        return middleCFreq * powf(2, semitoneOffset / 12.0f);
    }

private:
    /**
     * Read, Write, Notify, Indicate  Characteristic declaration helper.
     *
     * @tparam T type of data held by the characteristic.
     */
    template<typename T>
    class ReadWriteNotifyIndicateCharacteristic : public GattCharacteristic {
    public:
        /**
         * Construct a characteristic that can be read or written and emit
         * notification or indication.
         *
         * @param[in] uuid The UUID of the characteristic.
         * @param[in] initial_value Initial value contained by the characteristic.
         */
        ReadWriteNotifyIndicateCharacteristic(const UUID & uuid, const T& initial_value) :
            GattCharacteristic(
                /* UUID */ uuid,
                /* Initial value */ &_value,
                /* Value size */ sizeof(_value),
                /* Value capacity */ sizeof(_value),
                /* Properties */ GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_READ |
                                 GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_WRITE |
                                 GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_NOTIFY |
                                 GattCharacteristic::BLE_GATT_CHAR_PROPERTIES_INDICATE,
                /* Descriptors */ nullptr,
                /* Num descriptors */ 0,
                /* variable len */ false
            ),
            _value(initial_value) {
        }

        /**
         * Get the value of this characteristic.
         *
         * @param[in] server GattServer instance that contain the characteristic
         * value.
         * @param[in] dst Variable that will receive the characteristic value.
         *
         * @return BLE_ERROR_NONE in case of success or an appropriate error code.
         */
        ble_error_t get(GattServer &server, T& dst) const
        {
            uint16_t value_length = sizeof(dst);
            return server.read(getValueHandle(), &dst, &value_length);
        }

        /**
         * Assign a new value to this characteristic.
         *
         * @param[in] server GattServer instance that will receive the new value.
         * @param[in] value The new value to set.
         * @param[in] local_only Flag that determine if the change should be kept
         * locally or forwarded to subscribed clients.
         */
        ble_error_t set(GattServer &server, const uint8_t &value, bool local_only = false) const
        {
            return server.write(getValueHandle(), &value, sizeof(value), local_only);
        }

    private:
        uint8_t _value;
    };

private:
    uint8_t _initial_note = 0;
    GattService _note_service;
    GattCharacteristic _note_char;
    GattCharacteristic* _note_char_array[1];
    
    GattServer *_server = nullptr;
    events::EventQueue *_event_queue = nullptr;
};

int main()
{
    mbed_trace_init();

    BLE &ble = BLE::Instance();
    events::EventQueue event_queue;
    NoteService note_service;

    /* this process will handle basic ble setup and advertising for us */
    GattServerProcess ble_process(event_queue, ble);

    /* once it's done it will let us continue with our demo */
    ble_process.on_init(callback(&note_service, &NoteService::start));

    ble_process.start();

    return 0;
}
